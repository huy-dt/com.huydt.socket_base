package com.huydt.loto_online.server_sdk.core;

import com.huydt.loto_online.server_sdk.model.BotPlayer;
import com.huydt.loto_online.server_sdk.model.LotoPage;
import com.huydt.loto_online.server_sdk.model.LotoPlayer;
import com.huydt.loto_online.server_sdk.model.LotoRoom;
import com.huydt.socket_base.server.core.PlayerManager;
import com.huydt.socket_base.server.core.RoomManager;
import com.huydt.socket_base.server.network.IClientHandler;
import com.huydt.socket_base.server.model.Player;

import java.util.*;
import java.util.concurrent.*;

/**
 * Manages bots in a single {@link LotoRoom}.
 *
 * <p>Bots use a no-op {@link IClientHandler} — they don't need a real socket.
 * Bot buy/claim actions are delegated to {@link GameFlow} directly.
 */
public class BotManager {

    // ── No-op handler for bots ────────────────────────────────────

    static class BotHandler implements IClientHandler {
        private final String connId;
        BotHandler(String connId) { this.connId = connId; }
        @Override public void send(String json)   { /* bot ignores messages */ }
        @Override public void close()              { /* no socket */ }
        @Override public boolean isConnected()     { return true; }
        @Override public String getConnectionId()  { return connId; }
        @Override public String getRemoteIp()      { return "bot"; }
    }

    // ── State ─────────────────────────────────────────────────────

    private final GameFlow    flow;
    private final LotoRoom    room;
    private final PlayerManager playerManager;
    private final RoomManager   roomManager;
    private final LotoConfig    cfg;
    private final ScheduledExecutorService executor;
    private final Random rng = new Random();

    /** connId → BotPlayer */
    private final Map<String, BotPlayer> bots       = new LinkedHashMap<>();
    /** name (lowercase) → connId */
    private final Map<String, String>    nameToConn = new HashMap<>();

    BotManager(GameFlow flow, LotoRoom room,
               PlayerManager playerManager, RoomManager roomManager,
               LotoConfig cfg, ScheduledExecutorService executor) {
        this.flow          = flow;
        this.room          = room;
        this.playerManager = playerManager;
        this.roomManager   = roomManager;
        this.cfg           = cfg;
        this.executor      = executor;
    }

    // ── Public API ────────────────────────────────────────────────

    /**
     * Adds a bot to the room.
     * @return the created BotPlayer, or null if name already taken
     */
    public synchronized BotPlayer addBot(String name, long balance, int maxPages) {
        String key = name.toLowerCase().trim();
        if (nameToConn.containsKey(key)) return null;

        String     connId  = "bot-" + UUID.randomUUID().toString().substring(0, 8);
        BotPlayer  bot     = new BotPlayer(name, balance, maxPages);
        BotHandler handler = new BotHandler(connId);

        // Register via PlayerManager — bot joins the room like a normal player
        playerManager.join(connId, name, handler);
        roomManager.joinRoom(playerManager.getByConnId(connId), room.getId(), null);

        bots.put(connId, bot);
        nameToConn.put(key, connId);

        System.out.printf("[BOT] Added: name=%-10s balance=%,d maxPages=%d%n", name, balance, maxPages);
        scheduleBotBuy(connId, bot);
        return bot;
    }

    /**
     * Removes a bot by name.
     * @return true if found and removed
     */
    public synchronized boolean removeBot(String name) {
        String connId = nameToConn.remove(name.toLowerCase().trim());
        if (connId == null) return false;
        bots.remove(connId);
        roomManager.leaveRoom(playerManager.getByConnId(connId), true);
        playerManager.kick(playerManager.getByConnId(connId).getId(), "Bot removed");
        System.out.printf("[BOT] Removed: %s%n", name);
        return true;
    }

    public synchronized List<BotPlayer> listBots() { return new ArrayList<>(bots.values()); }
    public synchronized boolean hasBot(String name) { return nameToConn.containsKey(name.toLowerCase().trim()); }

    // ── Game event hooks ──────────────────────────────────────────

    /** Called when game starts — bots already bought pages during WAITING/VOTING. */
    public void onGameStarted() { /* pages bought during scheduling already */ }

    /**
     * Called every time a number is drawn. Bots check all pages and claim
     * if any row is complete, with a small random delay to feel natural.
     */
    public void onNumberDrawn(List<Integer> drawnNumbers) {
        List<Map.Entry<String, BotPlayer>> snapshot;
        synchronized (this) { snapshot = new ArrayList<>(bots.entrySet()); }

        for (Map.Entry<String, BotPlayer> entry : snapshot) {
            String    connId = entry.getKey();
            BotPlayer bot    = entry.getValue();

            for (LotoPage page : bot.getPages()) {
                if (page.hasWinningRow(drawnNumbers)) {
                    int delay  = 50 + rng.nextInt(350);
                    int pageId = page.getId();
                    executor.schedule(() -> {
                        GameState st = flow.getState();
                        if (st == GameState.PLAYING || st == GameState.ENDED) {
                            flow.claimWin(connId, pageId);
                        }
                    }, delay, TimeUnit.MILLISECONDS);
                    break; // one claim per draw
                }
            }
        }
    }

    /** Called when room resets — bots re-buy for the next round. */
    public synchronized void onRoomReset() {
        System.out.printf("[BOT] %d bot(s) mua giấy cho ván mới...%n", bots.size());
        new ArrayList<>(bots.entrySet()).forEach(e -> scheduleBotBuy(e.getKey(), e.getValue()));
    }

    // ── Internal ──────────────────────────────────────────────────

    private void scheduleBotBuy(String connId, BotPlayer bot) {
        int count   = 1 + rng.nextInt(bot.getMaxPages());
        int delayMs = 300 + rng.nextInt(700);
        executor.schedule(() -> {
            GameState st = flow.getState();
            if (st == GameState.WAITING || st == GameState.VOTING) {
                buyPagesForBot(connId, bot, count);
            }
        }, delayMs, TimeUnit.MILLISECONDS);
    }

    private synchronized void buyPagesForBot(String connId, BotPlayer bot, int count) {
        GameState state = flow.getState();
        if (state != GameState.WAITING && state != GameState.VOTING) return;

        count = Math.min(count, cfg.maxPagesPerBuy);
        long totalCost = room.getPricePerPage() * count;

        List<LotoPage> newPages = new ArrayList<>();
        for (int i = 0; i < count; i++) newPages.add(new LotoPage(flow.nextPageId()));
        bot.addPages(newPages);

        if (room.getPricePerPage() > 0 && bot.getBalance() >= totalCost) {
            bot.deduct(totalCost, String.format("Bot mua %d tờ × %,d", count, room.getPricePerPage()));
            room.addToJackpot(totalCost);
        }

        flow.broadcastRoomUpdate();
        System.out.printf("[BOT] %s mua %d tờ%n", bot.getName(), count);
    }

    public void shutdown() { /* executor is shared with GameFlow, not shut down here */ }
}

package com.huydt.loto_online.server_sdk.core;

import com.huydt.loto_online.server_sdk.callback.LotoServerCallback;
import com.huydt.loto_online.server_sdk.model.LotoPage;
import com.huydt.loto_online.server_sdk.model.LotoPlayer;
import com.huydt.loto_online.server_sdk.model.LotoRoom;
import com.huydt.loto_online.server_sdk.protocol.LotoOutboundMsg;
import com.huydt.socket_base.server.core.PlayerManager;
import com.huydt.socket_base.server.core.RoomManager;
import com.huydt.socket_base.server.model.Player;
import com.huydt.socket_base.server.model.Room;

import java.util.*;
import java.util.concurrent.*;
import java.util.concurrent.atomic.AtomicInteger;
import java.util.stream.Collectors;

/**
 * Manages the full game lifecycle for a single {@link LotoRoom}.
 *
 * <p>One {@code GameFlow} instance per room, created by {@link LotoDispatcher}
 * when a room is first used.
 *
 * <h3>State machine</h3>
 * <pre>
 *   WAITING → (vote or autoStart) → PLAYING → (win/all drawn) → ENDED
 *                     ↕                ↕
 *                   VOTING           PAUSED
 *                                      ↓
 *                                   PLAYING
 *   Any state → cancelGame() → ENDED
 *   ENDED    → reset()       → WAITING
 * </pre>
 */
public class GameFlow {

    private final LotoRoom      room;
    private final PlayerManager playerManager;
    private final RoomManager   roomManager;
    private final LotoConfig    cfg;
    private final LotoServerCallback callback;
    private final String        roomId;

    // ── Number pool ───────────────────────────────────────────────
    private final List<Integer>  numberPool    = new ArrayList<>();
    private final AtomicInteger  pageIdCounter = new AtomicInteger(1);

    // ── Voting ────────────────────────────────────────────────────
    private final Set<String> votedPlayerIds = ConcurrentHashMap.newKeySet();

    // ── Winner tracking ───────────────────────────────────────────
    // (stored in LotoRoom.winnerIds for persistence)

    // ── Scheduler ────────────────────────────────────────────────
    private final ScheduledExecutorService scheduler;

    private ScheduledFuture<?> drawTask;
    private ScheduledFuture<?> autoResetTask;
    private ScheduledFuture<?> autoStartTask;

    // ── Bot manager ───────────────────────────────────────────────
    private BotManager botManager;

    public GameFlow(LotoRoom room, PlayerManager playerManager, RoomManager roomManager,
                    LotoConfig cfg, LotoServerCallback callback) {
        this.room          = room;
        this.playerManager = playerManager;
        this.roomManager   = roomManager;
        this.cfg           = cfg;
        this.callback      = callback;
        this.roomId        = room.getId();
        // Scheduler init after roomId is assigned
        String shortId = roomId.length() >= 4 ? roomId.substring(0, 4) : roomId;
        this.scheduler = Executors.newSingleThreadScheduledExecutor(r -> {
            Thread t = new Thread(r, "loto-flow-" + shortId);
            t.setDaemon(true);
            return t;
        });
        buildNumberPool();
    }

    // ── BotManager lazy init ──────────────────────────────────────

    public synchronized BotManager getBotManager() {
        if (botManager == null) {
            botManager = new BotManager(this, room, playerManager, roomManager, cfg, scheduler);
        }
        return botManager;
    }

    // ── Auto-start ────────────────────────────────────────────────

    /**
     * Called after every buyPages / disconnect — re-evaluates whether auto-start
     * countdown should start, continue, or cancel.
     *
     * Condition: autoStartMs > 0, state WAITING/VOTING,
     * at least 1 real connected player has bought ≥ 1 page.
     */
    public synchronized void checkAutoStart() {
        if (cfg.autoStartMs <= 0) return;
        GameState state = getState();
        if (state == GameState.PLAYING || state == GameState.ENDED || state == GameState.PAUSED) return;

        boolean hasRealBuyer = realPlayersInRoom()
                .anyMatch(p -> !p.getPages().isEmpty());

        if (hasRealBuyer) {
            if (autoStartTask == null || autoStartTask.isDone()) {
                scheduleAutoStart(cfg.autoStartMs);
            }
        } else {
            cancelAutoStart();
        }
    }

    private void scheduleAutoStart(int delayMs) {
        cancelAutoStartSilent();
        broadcastToRoom(LotoOutboundMsg.autoStartScheduled(delayMs).toJson());
        if (callback != null) callback.onAutoStartScheduled(roomId, delayMs);

        autoStartTask = scheduler.schedule(() -> {
            synchronized (GameFlow.this) {
                GameState st = getState();
                if (st == GameState.WAITING || st == GameState.VOTING) {
                    boolean hasRealBuyer = realPlayersInRoom().anyMatch(p -> !p.getPages().isEmpty());
                    if (hasRealBuyer) startGame();
                }
            }
        }, delayMs, TimeUnit.MILLISECONDS);
    }

    public synchronized void cancelAutoStart() {
        cancelAutoStartSilent();
        broadcastToRoom(LotoOutboundMsg.autoStartScheduled(0).toJson());
        if (callback != null) callback.onAutoStartScheduled(roomId, 0);
    }

    private void cancelAutoStartSilent() {
        if (autoStartTask != null && !autoStartTask.isDone()) autoStartTask.cancel(false);
        autoStartTask = null;
    }

    public synchronized void setAutoStartMs(int ms) {
        if (ms < 0) ms = 0;
        int old = room.getAutoStartMs();
        room.setAutoStartMs(ms);
        if (callback != null) callback.onAutoStartMsChanged(roomId, old, ms);
        if (ms == 0) cancelAutoStart();
        else checkAutoStart();
    }

    // ── Voting ────────────────────────────────────────────────────

    public synchronized void voteStart(String connId) {
        GameState state = getState();
        if (state == GameState.PLAYING || state == GameState.ENDED) return;

        LotoPlayer player = lotoPlayer(connId);
        if (player == null || player.isBot()) return;

        room.setState(Room.RoomState.STARTING);
        votedPlayerIds.add(player.getId());

        int needed    = voteThreshold();
        int realVotes = (int) votedPlayerIds.stream()
                .map(id -> playerManager.getById(id))
                .filter(p -> p instanceof LotoPlayer && !((LotoPlayer) p).isBot())
                .count();

        broadcastToRoom(LotoOutboundMsg.voteUpdate(realVotes, needed).toJson());
        if (callback != null) callback.onVoteUpdate(roomId, realVotes, needed);

        if (realVotes >= needed) startGame();
    }

    // ── Game start ────────────────────────────────────────────────

    public synchronized void serverStart() {
        GameState state = getState();
        if (state == GameState.ENDED) {
            System.err.println("[GameFlow:" + roomId + "] serverStart() ignored — ENDED. Call reset() first.");
            return;
        }
        if (state != GameState.PLAYING) startGame();
    }

    private void startGame() {
        room.setState(Room.RoomState.PLAYING);
        cancelAutoStartSilent();

        broadcastToRoom(LotoOutboundMsg.gameStarting(room.getDrawIntervalMs()).toJson());
        if (callback != null) callback.onGameStarting(roomId);
        if (botManager != null) botManager.onGameStarted();

        drawTask = scheduler.scheduleAtFixedRate(
                this::drawNextNumber,
                room.getDrawIntervalMs(), room.getDrawIntervalMs(), TimeUnit.MILLISECONDS);
    }

    // ── Draw loop ─────────────────────────────────────────────────

    private synchronized void drawNextNumber() {
        if (getState() != GameState.PLAYING) { stopDrawing(); return; }

        if (numberPool.isEmpty()) {
            stopDrawing();
            room.setState(Room.RoomState.ENDED);
            String reason = "Hết 90 số — không có người thắng";
            broadcastToRoom(LotoOutboundMsg.gameEndedByServer(reason).toJson());
            broadcastRoomUpdate();
            if (callback != null) callback.onGameEndedByServer(roomId, reason);
            if (room.getAutoResetDelayMs() > 0) scheduleAutoReset(room.getAutoResetDelayMs());
            return;
        }

        int number = numberPool.remove(numberPool.size() - 1);
        room.addDrawnNumber(number);

        broadcastToRoom(LotoOutboundMsg.numberDrawn(number).toJson());
        if (callback != null) callback.onNumberDrawn(roomId, number, room.getDrawnNumbers());
        if (botManager != null) botManager.onNumberDrawn(new ArrayList<>(room.getDrawnNumbers()));
    }

    // ── Win claim ─────────────────────────────────────────────────

    public synchronized void claimWin(String connId, int pageId) {
        GameState state = getState();
        if (state != GameState.PLAYING && state != GameState.ENDED && state != GameState.PAUSED) return;

        LotoPlayer player = lotoPlayer(connId);
        if (player == null) return;

        if (room.getWinnerIds().contains(player.getId())) {
            playerManager.sendTo(connId, com.huydt.socket_base.server.protocol.OutboundMsg
                    .error("ALREADY_CLAIMED", "Bạn đã được xác nhận thắng rồi").toJson());
            return;
        }

        LotoPage page = player.getPageById(pageId);
        if (page == null) {
            playerManager.sendTo(connId, com.huydt.socket_base.server.protocol.OutboundMsg
                    .error("INVALID_PAGE", "Page not found").toJson());
            return;
        }

        broadcastToRoom(LotoOutboundMsg.claimReceived(player.getId(), player.getName(), pageId).toJson());
        if (callback != null) callback.onClaimReceived(roomId, player, pageId);

        if (cfg.autoVerifyWin) {
            if (page.hasWinningRow(new ArrayList<>(room.getDrawnNumbers()))) confirmWin(player.getId(), pageId);
            else rejectWin(player.getId(), pageId);
        }
    }

    public synchronized void confirmWin(String playerId, int pageId) {
        GameState state = getState();
        if (state != GameState.PLAYING && state != GameState.PAUSED && state != GameState.ENDED) return;

        LotoPlayer player = (LotoPlayer) playerManager.getById(playerId);
        if (player == null) return;

        if (state == GameState.PLAYING || state == GameState.PAUSED) {
            stopDrawing();
            room.setState(Room.RoomState.ENDED);
        }

        room.addWinnerId(playerId);

        broadcastToRoom(LotoOutboundMsg.winConfirmed(player.getId(), player.getName(), pageId).toJson());
        if (room.getWinnerIds().size() == 1) {
            broadcastToRoom(LotoOutboundMsg.gameEnded(player.getId(), player.getName()).toJson());
        }
        broadcastRoomUpdate();

        if (callback != null) {
            callback.onWinConfirmed(roomId, player, pageId, room.getJackpot());
            if (room.getWinnerIds().size() == 1) callback.onGameEnded(roomId, player, room.getJackpot());
        }

        if (room.getWinnerIds().size() == 1 && room.getAutoResetDelayMs() > 0) {
            scheduleAutoReset(room.getAutoResetDelayMs());
        }
    }

    public synchronized void rejectWin(String playerId, int pageId) {
        LotoPlayer player = (LotoPlayer) playerManager.getById(playerId);
        if (player == null) return;
        broadcastToRoom(LotoOutboundMsg.winRejected(player.getId(), pageId).toJson());
        if (callback != null) callback.onWinRejected(roomId, player, pageId);
    }

    // ── Pause / Resume ────────────────────────────────────────────

    public synchronized void pauseGame() {
        if (getState() != GameState.PLAYING) return;
        stopDrawing();
        room.setState(Room.RoomState.PAUSED);
        broadcastToRoom(LotoOutboundMsg.gamePaused().toJson());
        if (callback != null) callback.onGamePaused(roomId);
    }

    public synchronized void resumeGame() {
        if (getState() != GameState.PAUSED) return;
        room.setState(Room.RoomState.PLAYING);
        drawTask = scheduler.scheduleAtFixedRate(
                this::drawNextNumber,
                room.getDrawIntervalMs(), room.getDrawIntervalMs(), TimeUnit.MILLISECONDS);
        broadcastToRoom(LotoOutboundMsg.gameResumed(room.getDrawIntervalMs()).toJson());
        if (callback != null) callback.onGameResumed(roomId);
    }

    // ── Server cancel / end ───────────────────────────────────────

    public synchronized void serverEnd(String reason) {
        GameState state = getState();
        if (state != GameState.PLAYING && state != GameState.PAUSED) return;
        stopDrawing();
        room.setState(Room.RoomState.ENDED);
        String msg = reason != null ? reason : "Server kết thúc game";
        broadcastToRoom(LotoOutboundMsg.gameEndedByServer(msg).toJson());
        broadcastRoomUpdate();
        if (callback != null) callback.onGameEndedByServer(roomId, msg);
        if (room.getAutoResetDelayMs() > 0) scheduleAutoReset(room.getAutoResetDelayMs());
    }

    public synchronized void cancelGame(String reason) {
        if (getState() == GameState.ENDED) return;
        stopDrawing();
        room.setState(Room.RoomState.ENDED);

        long totalRefunded = 0;
        for (Player p : room.getPlayers()) {
            LotoPlayer lp = (LotoPlayer) p;
            int pages     = lp.getPages().size();
            long refund   = pages * room.getPricePerPage();
            if (refund > 0) {
                lp.refund(refund, String.format("Hoàn tiền — game hủy (%d tờ × %d)", pages, room.getPricePerPage()));
                totalRefunded += refund;
                sendBalanceUpdate(lp);
            }
        }
        room.resetJackpot();
        room.setPendingPricePerPage(-1);

        broadcastToRoom(LotoOutboundMsg.gameCancelled(reason, totalRefunded).toJson());
        broadcastRoomUpdate();
        if (callback != null) callback.onGameCancelled(roomId, reason, totalRefunded);
    }

    // ── Reset ─────────────────────────────────────────────────────

    public synchronized void reset() {
        stopDrawing();
        cancelAutoResetSilent();

        // Pay jackpot to winners
        long prizeEach   = 0;
        int  winnerCount = room.getWinnerIds().size();
        if (winnerCount > 0 && room.getJackpot() > 0) {
            prizeEach = room.getJackpot() / winnerCount;
            for (String wid : room.getWinnerIds()) {
                LotoPlayer w = (LotoPlayer) playerManager.getById(wid);
                if (w == null) continue;
                w.addPrize(prizeEach, String.format("Jackpot chia %d người: %,d", winnerCount, prizeEach));
                sendBalanceUpdate(w);
            }
            if (callback != null) callback.onJackpotPaid(roomId, new ArrayList<>(room.getWinnerIds()), prizeEach);
        }

        // Reset state
        room.setState(Room.RoomState.WAITING);
        room.clearDrawnNumbers();
        room.resetJackpot();
        room.clearWinnerIds();
        room.applyPendingPrice();
        votedPlayerIds.clear();
        buildNumberPool();
        pageIdCounter.set(1);

        for (Player p : room.getPlayers()) ((LotoPlayer) p).clearPages();

        broadcastToRoom(LotoOutboundMsg.roomReset(prizeEach, winnerCount).toJson());
        broadcastRoomUpdate();
        if (callback != null) callback.onRoomReset(roomId, prizeEach, winnerCount);
        if (botManager != null) botManager.onRoomReset();
    }

    // ── Settings ──────────────────────────────────────────────────

    public synchronized void setDrawInterval(int ms) {
        if (ms < 200) ms = 200;
        int old = room.getDrawIntervalMs();
        room.setDrawIntervalMs(ms);
        if (getState() == GameState.PLAYING) {
            stopDrawing();
            drawTask = scheduler.scheduleAtFixedRate(
                    this::drawNextNumber, ms, ms, TimeUnit.MILLISECONDS);
        }
        broadcastToRoom(LotoOutboundMsg.drawIntervalChanged(ms).toJson());
        if (callback != null) callback.onDrawIntervalChanged(roomId, old, ms);
    }

    public synchronized void setPricePerPage(long price) {
        if (price < 0) price = 0;
        long old = room.getPricePerPage();

        boolean realPlayerBought = room.getPlayers().stream()
                .filter(p -> p instanceof LotoPlayer)
                .map(p -> (LotoPlayer) p)
                .anyMatch(p -> !p.isBot() && p.isConnected() && !p.getPages().isEmpty());
        GameState state = getState();

        if (realPlayerBought || state == GameState.PLAYING || state == GameState.PAUSED) {
            room.setPendingPricePerPage(price);
        } else {
            room.setPricePerPage(price);
            room.setPendingPricePerPage(-1);
        }
        broadcastToRoom(LotoOutboundMsg.pricePerPageChanged(price).toJson());
        if (callback != null) callback.onPricePerPageChanged(roomId, old, price);
    }

    public synchronized void setAutoResetDelay(int ms) {
        if (ms < 0) ms = 0;
        int old = room.getAutoResetDelayMs();
        room.setAutoResetDelayMs(ms);
        if (callback != null) callback.onAutoResetDelayChanged(roomId, old, ms);
        if (ms == 0) cancelAutoReset();
        else if (getState() == GameState.ENDED) scheduleAutoReset(ms);
        else broadcastToRoom(LotoOutboundMsg.autoResetScheduled(ms).toJson());
    }

    public synchronized void scheduleAutoReset(int delayMs) {
        cancelAutoResetSilent();
        broadcastToRoom(LotoOutboundMsg.autoResetScheduled(delayMs).toJson());
        if (callback != null) callback.onAutoResetScheduled(roomId, delayMs);
        if (delayMs <= 0) { reset(); return; }
        autoResetTask = scheduler.schedule(() -> {
            synchronized (GameFlow.this) { reset(); }
        }, delayMs, TimeUnit.MILLISECONDS);
    }

    public synchronized void cancelAutoReset() {
        cancelAutoResetSilent();
        broadcastToRoom(LotoOutboundMsg.autoResetScheduled(0).toJson());
        if (callback != null) callback.onAutoResetScheduled(roomId, 0);
    }

    private void cancelAutoResetSilent() {
        if (autoResetTask != null && !autoResetTask.isDone()) autoResetTask.cancel(false);
        autoResetTask = null;
    }

    // ── Page counter (used by dispatcher to assign unique IDs) ────

    public int nextPageId() { return pageIdCounter.getAndIncrement(); }

    // ── Vote helpers ──────────────────────────────────────────────

    public int voteCount() { return votedPlayerIds.size(); }

    public int voteThreshold() {
        long realCount = realPlayersInRoom().count();
        return Math.max(1, (int) Math.ceil(realCount * cfg.voteThresholdPct / 100.0));
    }

    // ── State ─────────────────────────────────────────────────────

    public GameState getState() {
        return GameState.fromRoomState(room.getState());
    }

    // ── Shutdown ──────────────────────────────────────────────────

    public void shutdown() {
        stopDrawing();
        if (autoResetTask != null) autoResetTask.cancel(false);
        if (autoStartTask != null) autoStartTask.cancel(false);
        if (botManager != null) botManager.shutdown();
        scheduler.shutdownNow();
    }

    // ── Internal helpers ──────────────────────────────────────────

    void stopDrawing() {
        if (drawTask != null && !drawTask.isDone()) drawTask.cancel(false);
    }

    private void buildNumberPool() {
        numberPool.clear();
        for (int i = 1; i <= 90; i++) numberPool.add(i);
        Collections.shuffle(numberPool);
    }

    private java.util.stream.Stream<LotoPlayer> realPlayersInRoom() {
        return room.getPlayers().stream()
                .filter(p -> p instanceof LotoPlayer)
                .map(p -> (LotoPlayer) p)
                .filter(p -> !p.isBot() && p.isConnected());
    }

    private LotoPlayer lotoPlayer(String connId) {
        Player p = playerManager.getByConnId(connId);
        return (p instanceof LotoPlayer) ? (LotoPlayer) p : null;
    }

    private void broadcastToRoom(String json) {
        roomManager.broadcastToRoom(room, json, null);
    }

    void broadcastRoomUpdate() {
        roomManager.broadcastToRoom(room, com.huydt.socket_base.server.protocol.OutboundMsg
                .roomSnapshot(room).toJson(), null);
    }

    public void sendBalanceUpdate(LotoPlayer player) {
        List<com.huydt.loto_online.server_sdk.model.Transaction> txList = player.getTransactions();
        if (txList.isEmpty()) return;
        com.huydt.loto_online.server_sdk.model.Transaction last = txList.get(txList.size() - 1);
        String connId = room.getPlayers().stream()
                .filter(p -> p.getId().equals(player.getId()))
                .map(com.huydt.socket_base.server.model.Player::getConnId)
                .findFirst().orElse(null);
        if (connId != null) {
            playerManager.sendTo(connId,
                    LotoOutboundMsg.balanceUpdate(player.getId(), player.getBalance(), last).toJson());
        }
    }
}

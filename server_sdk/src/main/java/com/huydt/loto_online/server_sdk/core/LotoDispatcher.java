package com.huydt.loto_online.server_sdk.core;

import com.huydt.loto_online.server_sdk.model.LotoPage;
import com.huydt.loto_online.server_sdk.model.LotoPlayer;
import com.huydt.loto_online.server_sdk.model.LotoRoom;
import com.huydt.loto_online.server_sdk.protocol.LotoOutboundMsg;
import com.huydt.socket_base.server.core.PlayerManager;
import com.huydt.socket_base.server.core.RoomManager;
import com.huydt.socket_base.server.event.EventBus;
import com.huydt.socket_base.server.model.Player;
import com.huydt.socket_base.server.model.Room;
import com.huydt.socket_base.server.network.IClientHandler;
import com.huydt.socket_base.server.network.MessageDispatcher;
import com.huydt.socket_base.server.protocol.InboundMsg;
import com.huydt.socket_base.server.protocol.OutboundMsg;

import java.util.ArrayList;
import java.util.List;
import java.util.Map;
import java.util.concurrent.ConcurrentHashMap;

/**
 * Extends {@link MessageDispatcher} to handle all Loto-specific CUSTOM messages.
 *
 * <h3>CUSTOM message tags (Client → Server)</h3>
 * <pre>
 *   BUY_PAGE       { roomId, count }
 *   CHANGE_PAGE    { roomId, pageId }
 *   VOTE_START     { roomId }
 *   CLAIM_WIN      { roomId, pageId }
 *   GET_PAGES      { roomId, playerId? }  — omit playerId = own pages
 *   GET_WALLET     { roomId }
 *
 *   — Admin only —
 *   GAME_START     { roomId }
 *   GAME_END       { roomId, reason? }
 *   GAME_CANCEL    { roomId, reason? }
 *   GAME_PAUSE     { roomId }
 *   GAME_RESUME    { roomId }
 *   GAME_RESET     { roomId }
 *   CONFIRM_WIN    { roomId, playerId, pageId }
 *   REJECT_WIN     { roomId, playerId, pageId }
 *   TOP_UP         { roomId, playerId, amount, note? }
 *   SET_DRAW_INTERVAL    { roomId, ms }
 *   SET_PRICE_PER_PAGE   { roomId, price }
 *   SET_AUTO_RESET_DELAY { roomId, ms }
 *   SET_AUTO_START_MS    { roomId, ms }
 *   BOT_ADD        { roomId, name, balance, maxPages }
 *   BOT_REMOVE     { roomId, name }
 * </pre>
 */
public class LotoDispatcher extends MessageDispatcher {

    private final LotoConfig lotoConfig;
    /** roomId → GameFlow */
    private final Map<String, GameFlow> flows = new ConcurrentHashMap<>();

    public LotoDispatcher(PlayerManager playerManager, RoomManager roomManager,
                          EventBus bus, String adminToken, LotoConfig lotoConfig) {
        super(playerManager, roomManager, bus, adminToken);
        this.lotoConfig = lotoConfig;
    }

    // ── Extension point ───────────────────────────────────────────

    @Override
    protected void dispatchCustom(String connId, InboundMsg msg, IClientHandler handler) {
        String tag    = msg.getString("tag");
        String roomId = msg.getString("roomId");

        if (tag == null) {
            handler.send(OutboundMsg.error("MISSING_TAG", "tag required").toJson());
            return;
        }
        if (roomId == null) {
            handler.send(OutboundMsg.error("MISSING_ROOM_ID", "roomId required in payload").toJson());
            return;
        }

        LotoRoom room = lotoRoom(roomId);
        if (room == null) {
            handler.send(OutboundMsg.error("ROOM_NOT_FOUND", "Room not found: " + roomId).toJson());
            return;
        }

        GameFlow flow = getOrCreateFlow(room);

        switch (tag) {
            // ── Player actions ────────────────────────────────────────────
            case "BUY_PAGE":    handleBuyPage(connId, msg, handler, room, flow);     break;
            case "CHANGE_PAGE": handleChangePage(connId, msg, handler, room, flow);  break;
            case "VOTE_START":  flow.voteStart(connId);                              break;
            case "CLAIM_WIN":   handleClaimWin(connId, msg, handler, flow);          break;
            case "GET_PAGES":   handleGetPages(connId, msg, handler, room);          break;
            case "GET_WALLET":  handleGetWallet(connId, handler, room);              break;

            // ── Admin actions ─────────────────────────────────────────────
            case "GAME_START":  requireAdmin(connId, handler, () -> flow.serverStart());                                          break;
            case "GAME_END":    requireAdmin(connId, handler, () -> flow.serverEnd(msg.getString("reason")));                     break;
            case "GAME_CANCEL": requireAdmin(connId, handler, () -> flow.cancelGame(msg.getString("reason")));                    break;
            case "GAME_PAUSE":  requireAdmin(connId, handler, () -> flow.pauseGame());                                            break;
            case "GAME_RESUME": requireAdmin(connId, handler, () -> flow.resumeGame());                                           break;
            case "GAME_RESET":  requireAdmin(connId, handler, () -> flow.reset());                                                break;
            case "CONFIRM_WIN": requireAdmin(connId, handler, () -> handleConfirmWin(msg, flow));                                 break;
            case "REJECT_WIN":  requireAdmin(connId, handler, () -> handleRejectWin(msg, flow));                                  break;
            case "TOP_UP":      requireAdmin(connId, handler, () -> handleTopUp(msg, room));                                      break;
            case "SET_DRAW_INTERVAL":    requireAdmin(connId, handler, () -> flow.setDrawInterval(msg.getInt("ms", 5000)));       break;
            case "SET_PRICE_PER_PAGE":   requireAdmin(connId, handler, () -> flow.setPricePerPage(msg.getLong("price", 0)));      break;
            case "SET_AUTO_RESET_DELAY": requireAdmin(connId, handler, () -> flow.setAutoResetDelay(msg.getInt("ms", 0)));        break;
            case "SET_AUTO_START_MS":    requireAdmin(connId, handler, () -> flow.setAutoStartMs(msg.getInt("ms", 0)));           break;
            case "BOT_ADD":     requireAdmin(connId, handler, () -> handleBotAdd(msg, handler, flow));                            break;
            case "BOT_REMOVE":  requireAdmin(connId, handler, () -> handleBotRemove(msg, handler, flow));                         break;

            default:
                handler.send(OutboundMsg.error("UNKNOWN_TAG", "Unknown loto tag: " + tag).toJson());
        }
    }

    // ── Override sendSnapshotTo — send loto WELCOME instead of generic snapshot ──

    @Override
    protected void sendSnapshotTo(IClientHandler handler, Room room) {
        if (!(room instanceof LotoRoom)) {
            super.sendSnapshotTo(handler, room);
            return;
        }
        Player p = playerManager.getByConnId(handler.getConnectionId());
        if (!(p instanceof LotoPlayer)) {
            super.sendSnapshotTo(handler, room);
            return;
        }
        sendLotoWelcome(handler.getConnectionId(), (LotoPlayer) p, (LotoRoom) room);
    }

    // ── Loto WELCOME ──────────────────────────────────────────────

    private void sendLotoWelcome(String connId, LotoPlayer player, LotoRoom room) {
        GameFlow flow      = getOrCreateFlow(room);
        boolean  isPaused  = flow.getState() == GameState.PAUSED;
        int      voteNeeded = flow.voteThreshold();
        int      voteCount  = flow.voteCount();

        playerManager.sendTo(connId, LotoOutboundMsg.welcome(
                player,
                new ArrayList<>(playerManager.getAllPlayers()),
                room,
                isPaused,
                room.getDrawnNumbers(),
                voteCount, voteNeeded
        ).toJson());
    }

    // ── Player handlers ───────────────────────────────────────────

    private void handleBuyPage(String connId, InboundMsg msg, IClientHandler handler,
                                LotoRoom room, GameFlow flow) {
        LotoPlayer player = lotoPlayer(connId);
        if (player == null) { handler.send(OutboundMsg.error("NOT_JOINED", "Send JOIN first").toJson()); return; }

        GameState state = flow.getState();
        if (state != GameState.WAITING && state != GameState.VOTING) {
            handler.send(OutboundMsg.error("GAME_STARTED", "Cannot buy pages after game started").toJson()); return;
        }

        int  count     = Math.min(Math.max(1, msg.getInt("count", 1)), lotoConfig.maxPagesPerBuy);
        long totalCost = room.getPricePerPage() * count;

        if (room.getPricePerPage() > 0 && player.getBalance() < totalCost) {
            handler.send(OutboundMsg.error("INSUFFICIENT_BALANCE",
                    String.format("Need %d, have %d", totalCost, player.getBalance())).toJson());
            return;
        }

        List<LotoPage> newPages = new ArrayList<>();
        for (int i = 0; i < count; i++) newPages.add(new LotoPage(flow.nextPageId()));
        player.addPages(newPages);

        if (room.getPricePerPage() > 0) {
            player.deduct(totalCost, String.format("Mua %d tờ × %d", count, room.getPricePerPage()));
            room.addToJackpot(totalCost);
        }

        handler.send(LotoOutboundMsg.pagesAssigned(player.getId(), newPages).toJson());
        flow.sendBalanceUpdate(player);
        notifyRoom(room, connId);   // broadcast ROOM_SNAPSHOT so others see pageCount update
        flow.checkAutoStart();
    }

    private void handleChangePage(String connId, InboundMsg msg, IClientHandler handler,
                                   LotoRoom room, GameFlow flow) {
        LotoPlayer player = lotoPlayer(connId);
        if (player == null) return;

        GameState state = flow.getState();
        if (state != GameState.WAITING && state != GameState.VOTING) {
            handler.send(OutboundMsg.error("GAME_STARTED", "Cannot change page after game started").toJson()); return;
        }

        int pageId = msg.getInt("pageId", -1);
        LotoPage page = player.getPageById(pageId);
        if (page == null) {
            handler.send(OutboundMsg.error("PAGE_NOT_FOUND", "Page #" + pageId + " not found").toJson()); return;
        }

        page.makePage();
        handler.send(LotoOutboundMsg.pageChanged(player.getId(), pageId, page).toJson());
    }

    private void handleClaimWin(String connId, InboundMsg msg, IClientHandler handler, GameFlow flow) {
        int pageId = msg.getInt("pageId", -1);
        if (pageId < 0) { handler.send(OutboundMsg.error("MISSING_FIELDS", "pageId required").toJson()); return; }
        flow.claimWin(connId, pageId);
    }

    private void handleGetPages(String connId, InboundMsg msg, IClientHandler handler, LotoRoom room) {
        String targetId = msg.getString("playerId");
        Player target   = targetId != null ? playerManager.getById(targetId)
                                           : playerManager.getByConnId(connId);
        if (!(target instanceof LotoPlayer)) {
            handler.send(OutboundMsg.error("PLAYER_NOT_FOUND", "Player not found").toJson()); return;
        }
        LotoPlayer lp = (LotoPlayer) target;
        handler.send(LotoOutboundMsg.playerPages(lp.getId(), lp.getName(), lp.getPages()).toJson());
    }

    private void handleGetWallet(String connId, IClientHandler handler, LotoRoom room) {
        LotoPlayer player = lotoPlayer(connId);
        if (player == null) return;
        handler.send(LotoOutboundMsg.walletHistory(player.getId(), player).toJson());
    }

    // ── Admin handlers ────────────────────────────────────────────

    private void handleConfirmWin(InboundMsg msg, GameFlow flow) {
        String playerId = msg.getString("playerId");
        int    pageId   = msg.getInt("pageId", -1);
        if (playerId != null && pageId >= 0) flow.confirmWin(playerId, pageId);
    }

    private void handleRejectWin(InboundMsg msg, GameFlow flow) {
        String playerId = msg.getString("playerId");
        int    pageId   = msg.getInt("pageId", -1);
        if (playerId != null && pageId >= 0) flow.rejectWin(playerId, pageId);
    }

    private void handleTopUp(InboundMsg msg, LotoRoom room) {
        String playerId = msg.getString("playerId");
        long   amount   = msg.getLong("amount", 0);
        if (playerId == null || amount <= 0) return;
        LotoPlayer player = (LotoPlayer) playerManager.getById(playerId);
        if (player == null) return;
        player.topUp(amount, msg.getString("note") != null ? msg.getString("note") : "Admin nạp tiền");
        GameFlow flow = getOrCreateFlow(room);
        flow.sendBalanceUpdate(player);
        notifyRoom(room, null);
    }

    private void handleBotAdd(InboundMsg msg, IClientHandler handler, GameFlow flow) {
        String name     = msg.getString("name");
        long   balance  = msg.getLong("balance", 0);
        int    maxPages = msg.getInt("maxPages", 1);
        if (name == null) { handler.send(OutboundMsg.error("MISSING_FIELDS", "name required").toJson()); return; }
        flow.getBotManager().addBot(name, balance, maxPages);
    }

    private void handleBotRemove(InboundMsg msg, IClientHandler handler, GameFlow flow) {
        String name = msg.getString("name");
        if (name == null) { handler.send(OutboundMsg.error("MISSING_FIELDS", "name required").toJson()); return; }
        boolean removed = flow.getBotManager().removeBot(name);
        if (!removed) handler.send(OutboundMsg.error("NOT_FOUND", "Bot not found: " + name).toJson());
    }

    // ── Helpers ───────────────────────────────────────────────────

    private GameFlow getOrCreateFlow(LotoRoom room) {
        return flows.computeIfAbsent(room.getId(), id ->
                new GameFlow(room, playerManager, roomManager, lotoConfig, null));
    }

    /** Called by LotoServer to inject the callback after construction. */
    public void setCallback(String roomId, com.huydt.loto_online.server_sdk.callback.LotoServerCallback cb) {
        // callback is passed on next getOrCreateFlow; for existing flows inject directly
        GameFlow flow = flows.get(roomId);
        // GameFlow.callback is final — so we pass it at construction via LotoServer.Builder
    }

    public GameFlow getFlow(String roomId) { return flows.get(roomId); }

    private LotoRoom lotoRoom(String roomId) {
        Room r = roomManager.getRoom(roomId);
        return (r instanceof LotoRoom) ? (LotoRoom) r : null;
    }

    private LotoPlayer lotoPlayer(String connId) {
        Player p = playerManager.getByConnId(connId);
        return (p instanceof LotoPlayer) ? (LotoPlayer) p : null;
    }
}

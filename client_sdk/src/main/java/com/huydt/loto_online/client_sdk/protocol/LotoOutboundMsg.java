package com.huydt.loto_online.client_sdk.protocol;

import com.huydt.socket_base.client.protocol.OutboundMsg;
import org.json.JSONObject;

/**
 * Factory for all Loto-specific outbound messages (Client → Server).
 *
 * <p>All messages are sent as {@code CUSTOM} type with a {@code tag} field
 * matching {@code LotoDispatcher} on the server side.
 *
 * <h3>Usage</h3>
 * <pre>
 * client.send(LotoOutboundMsg.buyPage(roomId, 3));
 * client.send(LotoOutboundMsg.voteStart(roomId));
 * client.send(LotoOutboundMsg.claimWin(roomId, pageId));
 *
 * // Admin
 * client.send(LotoOutboundMsg.gameStart(roomId));
 * client.send(LotoOutboundMsg.topUp(roomId, playerId, 50000, "bonus"));
 * client.send(LotoOutboundMsg.botAdd(roomId, "Bot1", 100000, 3));
 * </pre>
 */
public final class LotoOutboundMsg {

    private LotoOutboundMsg() {}

    // ── Helper ────────────────────────────────────────────────────────

    private static String custom(String tag, String roomId, JSONObject extra) {
        JSONObject data = extra != null ? extra : new JSONObject();
        data.put("roomId", roomId);
        return OutboundMsg.custom(tag, data);
    }

    // ── Player actions ────────────────────────────────────────────────

    /** BUY_PAGE — buy {@code count} pages in the given room. */
    public static String buyPage(String roomId, int count) {
        return custom("BUY_PAGE", roomId, new JSONObject().put("count", count));
    }

    /** CHANGE_PAGE — shuffle (re-roll) one page. */
    public static String changePage(String roomId, int pageId) {
        return custom("CHANGE_PAGE", roomId, new JSONObject().put("pageId", pageId));
    }

    /** VOTE_START — cast a vote to start the game. */
    public static String voteStart(String roomId) {
        return custom("VOTE_START", roomId, null);
    }

    /** CLAIM_WIN — claim that page {@code pageId} has a winning row. */
    public static String claimWin(String roomId, int pageId) {
        return custom("CLAIM_WIN", roomId, new JSONObject().put("pageId", pageId));
    }

    /** GET_PAGES — request page grids for a player (null = my own pages). */
    public static String getPages(String roomId, String playerId) {
        JSONObject d = new JSONObject();
        if (playerId != null) d.put("playerId", playerId);
        return custom("GET_PAGES", roomId, d);
    }

    /** GET_WALLET — request my full transaction history. */
    public static String getWallet(String roomId) {
        return custom("GET_WALLET", roomId, null);
    }

    // ── Admin: game flow ──────────────────────────────────────────────

    public static String gameStart(String roomId) {
        return custom("GAME_START", roomId, null);
    }

    public static String gameEnd(String roomId, String reason) {
        JSONObject d = new JSONObject();
        if (reason != null) d.put("reason", reason);
        return custom("GAME_END", roomId, d);
    }

    public static String gameCancel(String roomId, String reason) {
        JSONObject d = new JSONObject();
        if (reason != null) d.put("reason", reason);
        return custom("GAME_CANCEL", roomId, d);
    }

    public static String gamePause(String roomId) {
        return custom("GAME_PAUSE", roomId, null);
    }

    public static String gameResume(String roomId) {
        return custom("GAME_RESUME", roomId, null);
    }

    public static String gameReset(String roomId) {
        return custom("GAME_RESET", roomId, null);
    }

    // ── Admin: win management ─────────────────────────────────────────

    public static String confirmWin(String roomId, String playerId, int pageId) {
        return custom("CONFIRM_WIN", roomId,
                new JSONObject().put("playerId", playerId).put("pageId", pageId));
    }

    public static String rejectWin(String roomId, String playerId, int pageId) {
        return custom("REJECT_WIN", roomId,
                new JSONObject().put("playerId", playerId).put("pageId", pageId));
    }

    // ── Admin: player management ──────────────────────────────────────

    public static String topUp(String roomId, String playerId, long amount, String note) {
        JSONObject d = new JSONObject().put("playerId", playerId).put("amount", amount);
        if (note != null) d.put("note", note);
        return custom("TOP_UP", roomId, d);
    }

    // ── Admin: settings ───────────────────────────────────────────────

    public static String setDrawInterval(String roomId, int ms) {
        return custom("SET_DRAW_INTERVAL", roomId, new JSONObject().put("ms", ms));
    }

    public static String setPricePerPage(String roomId, long price) {
        return custom("SET_PRICE_PER_PAGE", roomId, new JSONObject().put("price", price));
    }

    public static String setAutoReset(String roomId, int ms) {
        return custom("SET_AUTO_RESET_DELAY", roomId, new JSONObject().put("ms", ms));
    }

    public static String setAutoStart(String roomId, int ms) {
        return custom("SET_AUTO_START_MS", roomId, new JSONObject().put("ms", ms));
    }

    // ── Admin: bots ───────────────────────────────────────────────────

    public static String botAdd(String roomId, String name, long balance, int maxPages) {
        return custom("BOT_ADD", roomId,
                new JSONObject().put("name", name).put("balance", balance).put("maxPages", maxPages));
    }

    public static String botRemove(String roomId, String name) {
        return custom("BOT_REMOVE", roomId, new JSONObject().put("name", name));
    }
}

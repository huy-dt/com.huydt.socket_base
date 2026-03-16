package com.huydt.loto_online.server_sdk.protocol;

import com.huydt.loto_online.server_sdk.model.LotoPage;
import com.huydt.loto_online.server_sdk.model.LotoPlayer;
import com.huydt.loto_online.server_sdk.model.Transaction;
import com.huydt.socket_base.server.protocol.OutboundMsg;
import org.json.JSONArray;
import org.json.JSONObject;

import java.util.List;

/**
 * Factory for all Loto-specific outbound messages.
 *
 * <p>All messages use {@link OutboundMsg#custom(String, JSONObject)} so they
 * travel as {@code CUSTOM_MSG} packets on the wire — no changes needed to
 * the base socket_base protocol.
 *
 * <h3>Tags (Server → Client)</h3>
 * <pre>
 *   WELCOME            — full state on join / reconnect
 *   PAGES_ASSIGNED     — pages issued to buyer
 *   PAGE_CHANGED       — one page shuffled
 *   NUMBER_DRAWN       — one number revealed
 *   VOTE_UPDATE        — vote count changed
 *   GAME_STARTING      — draw loop about to begin
 *   GAME_ENDED         — winner confirmed
 *   GAME_ENDED_SERVER  — server forced end (no winner)
 *   GAME_CANCELLED     — game cancelled, refunds issued
 *   GAME_PAUSED        — draw paused
 *   GAME_RESUMED       — draw resumed
 *   ROOM_RESET         — new round starting
 *   CLAIM_RECEIVED     — server received a win claim
 *   WIN_CONFIRMED      — claim accepted
 *   WIN_REJECTED       — claim rejected
 *   BALANCE_UPDATE     — single transaction applied
 *   WALLET_HISTORY     — full transaction ledger
 *   PLAYER_PAGES       — all pages of a player (on-demand)
 *   DRAW_INTERVAL_CHANGED
 *   PRICE_PER_PAGE_CHANGED
 *   AUTO_START_SCHEDULED
 *   AUTO_RESET_SCHEDULED
 * </pre>
 */
public final class LotoOutboundMsg {

    private LotoOutboundMsg() {}

    // ── Join / Reconnect ──────────────────────────────────────────

    public static OutboundMsg welcome(LotoPlayer player,
                                      List<com.huydt.socket_base.server.model.Player> allPlayers,
                                      com.huydt.loto_online.server_sdk.model.LotoRoom room,
                                      boolean isPaused,
                                      List<Integer> drawnNumbers,
                                      int voteCount, int voteNeeded) {
        JSONObject d = new JSONObject();
        d.put("player",        player.toJson());          // full private (balance, transactions)
        d.put("room",          room.toJson());             // full room (jackpot, drawnCount, ...)
        d.put("isPaused",      isPaused);
        d.put("voteCount",     voteCount);
        d.put("voteNeeded",    voteNeeded);

        // Pages with grids — only sent to owner
        JSONArray pagesArr = new JSONArray();
        for (LotoPage p : player.getPages()) {
            JSONObject pg = new JSONObject();
            pg.put("id",   p.getId());
            pg.put("page", new JSONArray(p.getPage()));
            pagesArr.put(pg);
        }
        d.put("pages", pagesArr);

        JSONArray drawnArr = new JSONArray();
        drawnNumbers.forEach(drawnArr::put);
        d.put("drawnNumbers", drawnArr);

        return OutboundMsg.custom("WELCOME", d);
    }

    // ── Pages ─────────────────────────────────────────────────────

    public static OutboundMsg pagesAssigned(String playerId, List<LotoPage> pages) {
        JSONObject d = new JSONObject();
        d.put("playerId", playerId);
        JSONArray arr = new JSONArray();
        for (LotoPage p : pages) {
            JSONObject pg = new JSONObject();
            pg.put("id",   p.getId());
            pg.put("page", new JSONArray(p.getPage()));
            arr.put(pg);
        }
        d.put("pages", arr);
        return OutboundMsg.custom("PAGES_ASSIGNED", d);
    }

    public static OutboundMsg pageChanged(String playerId, int pageId, LotoPage page) {
        JSONObject d = new JSONObject();
        d.put("playerId", playerId);
        d.put("pageId",   pageId);
        d.put("page",     new JSONArray(page.getPage()));
        return OutboundMsg.custom("PAGE_CHANGED", d);
    }

    public static OutboundMsg playerPages(String playerId, String playerName, List<LotoPage> pages) {
        JSONObject d = new JSONObject();
        d.put("playerId",   playerId);
        d.put("playerName", playerName);
        JSONArray arr = new JSONArray();
        for (LotoPage p : pages) {
            JSONObject pg = new JSONObject();
            pg.put("id",   p.getId());
            pg.put("page", new JSONArray(p.getPage()));
            arr.put(pg);
        }
        d.put("pages", arr);
        return OutboundMsg.custom("PLAYER_PAGES", d);
    }

    // ── Game flow ─────────────────────────────────────────────────

    public static OutboundMsg numberDrawn(int number) {
        JSONObject d = new JSONObject();
        d.put("number", number);
        return OutboundMsg.custom("NUMBER_DRAWN", d);
    }

    public static OutboundMsg voteUpdate(int votes, int needed) {
        JSONObject d = new JSONObject();
        d.put("voteCount",  votes);
        d.put("voteNeeded", needed);
        return OutboundMsg.custom("VOTE_UPDATE", d);
    }

    public static OutboundMsg gameStarting(int drawIntervalMs) {
        JSONObject d = new JSONObject();
        d.put("drawIntervalMs", drawIntervalMs);
        return OutboundMsg.custom("GAME_STARTING", d);
    }

    public static OutboundMsg gameEnded(String winnerId, String winnerName) {
        JSONObject d = new JSONObject();
        d.put("winnerId",   winnerId);
        d.put("winnerName", winnerName);
        return OutboundMsg.custom("GAME_ENDED", d);
    }

    public static OutboundMsg gameEndedByServer(String reason) {
        JSONObject d = new JSONObject();
        d.put("reason", reason);
        return OutboundMsg.custom("GAME_ENDED_SERVER", d);
    }

    public static OutboundMsg gameCancelled(String reason, long totalRefunded) {
        JSONObject d = new JSONObject();
        d.put("reason",        reason);
        d.put("totalRefunded", totalRefunded);
        return OutboundMsg.custom("GAME_CANCELLED", d);
    }

    public static OutboundMsg gamePaused() {
        return OutboundMsg.custom("GAME_PAUSED", new JSONObject());
    }

    public static OutboundMsg gameResumed(int drawIntervalMs) {
        JSONObject d = new JSONObject();
        d.put("drawIntervalMs", drawIntervalMs);
        return OutboundMsg.custom("GAME_RESUMED", d);
    }

    public static OutboundMsg roomReset(long prizePerWinner, int winnerCount) {
        JSONObject d = new JSONObject();
        d.put("prizePerWinner", prizePerWinner);
        d.put("winnerCount",    winnerCount);
        return OutboundMsg.custom("ROOM_RESET", d);
    }

    // ── Win claim ─────────────────────────────────────────────────

    public static OutboundMsg claimReceived(String playerId, String playerName, int pageId) {
        JSONObject d = new JSONObject();
        d.put("playerId",   playerId);
        d.put("playerName", playerName);
        d.put("pageId",     pageId);
        return OutboundMsg.custom("CLAIM_RECEIVED", d);
    }

    public static OutboundMsg winConfirmed(String playerId, String playerName, int pageId) {
        JSONObject d = new JSONObject();
        d.put("playerId",   playerId);
        d.put("playerName", playerName);
        d.put("pageId",     pageId);
        return OutboundMsg.custom("WIN_CONFIRMED", d);
    }

    public static OutboundMsg winRejected(String playerId, int pageId) {
        JSONObject d = new JSONObject();
        d.put("playerId", playerId);
        d.put("pageId",   pageId);
        return OutboundMsg.custom("WIN_REJECTED", d);
    }

    // ── Balance ───────────────────────────────────────────────────

    public static OutboundMsg balanceUpdate(String playerId, long balance, Transaction tx) {
        JSONObject d = new JSONObject();
        d.put("playerId",     playerId);
        d.put("balance",      balance);
        d.put("timestamp",    tx.getTimestamp());
        d.put("type",         tx.getType().name());
        d.put("amount",       tx.getAmount());
        d.put("balanceAfter", tx.getBalanceAfter());
        d.put("note",         tx.getNote());
        return OutboundMsg.custom("BALANCE_UPDATE", d);
    }

    public static OutboundMsg walletHistory(String playerId, LotoPlayer player) {
        JSONObject d = new JSONObject();
        d.put("playerId", playerId);
        d.put("balance",  player.getBalance());
        JSONArray arr = new JSONArray();
        for (Transaction tx : player.getTransactions()) {
            JSONObject t = new JSONObject();
            t.put("timestamp",    tx.getTimestamp());
            t.put("type",         tx.getType().name());
            t.put("amount",       tx.getAmount());
            t.put("balanceAfter", tx.getBalanceAfter());
            t.put("note",         tx.getNote());
            arr.put(t);
        }
        d.put("transactions", arr);
        return OutboundMsg.custom("WALLET_HISTORY", d);
    }

    // ── Settings change ───────────────────────────────────────────

    public static OutboundMsg drawIntervalChanged(int ms) {
        return OutboundMsg.custom("DRAW_INTERVAL_CHANGED", new JSONObject().put("drawIntervalMs", ms));
    }

    public static OutboundMsg pricePerPageChanged(long price) {
        return OutboundMsg.custom("PRICE_PER_PAGE_CHANGED", new JSONObject().put("pricePerPage", price));
    }

    public static OutboundMsg autoStartScheduled(int delayMs) {
        return OutboundMsg.custom("AUTO_START_SCHEDULED", new JSONObject().put("delayMs", delayMs));
    }

    public static OutboundMsg autoResetScheduled(int delayMs) {
        return OutboundMsg.custom("AUTO_RESET_SCHEDULED", new JSONObject().put("delayMs", delayMs));
    }
}

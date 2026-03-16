package com.huydt.loto_online.client_sdk.callback;

import com.huydt.loto_online.client_sdk.model.LotoPage;
import com.huydt.loto_online.client_sdk.model.LotoPlayerInfo;
import com.huydt.loto_online.client_sdk.model.LotoRoomInfo;

import java.util.List;

/**
 * Callback interface for all Loto client events.
 *
 * <p>All methods have empty default implementations — override only what you need.
 *
 * <h3>Usage</h3>
 * <pre>
 * LotoClient client = new LotoClient.Builder()
 *     .host("localhost").port(9001).name("Alice")
 *     .callback(new LotoClientCallback() {
 *         public void onNumberDrawn(int number, List&lt;Integer&gt; all) {
 *             updateUI(number);
 *         }
 *         public void onPagesAssigned(List&lt;LotoPage&gt; pages) {
 *             showPages(pages);
 *         }
 *     })
 *     .build();
 * </pre>
 */
public interface LotoClientCallback {

    // ── Connection ────────────────────────────────────────────────────

    default void onConnected() {}
    default void onDisconnected(String reason) {}

    // ── Session ───────────────────────────────────────────────────────

    /** WELCOME — successfully joined, session is populated. */
    default void onWelcome(String playerId, long balance) {}

    /** RECONNECTED — session restored after disconnect. */
    default void onReconnected(String playerId) {}

    // ── Room ──────────────────────────────────────────────────────────

    /** ROOM_SNAPSHOT — full room state pushed. */
    default void onRoomSnapshot(LotoRoomInfo room) {}

    /** A player joined the room. */
    default void onPlayerJoined(LotoPlayerInfo player) {}

    /** A player left permanently (leave / kick / timeout). */
    default void onPlayerLeft(String playerId) {}

    /** A player disconnected (ghost — may reconnect). */
    default void onPlayerDisconnected(String playerId) {}

    /** A ghost player reconnected. */
    default void onPlayerReconnected(LotoPlayerInfo player) {}

    /** A player's data changed (pageCount etc.). */
    default void onPlayerUpdate(LotoPlayerInfo player) {}

    // ── Game flow ─────────────────────────────────────────────────────

    /** VOTE_UPDATE — vote progress changed. */
    default void onVoteUpdate(int voteCount, int voteNeeded) {}

    /** GAME_STARTING — draw loop is about to begin. */
    default void onGameStarting(int drawIntervalMs) {}

    /**
     * NUMBER_DRAWN — a new number was revealed.
     * @param number     the drawn number (1–90)
     * @param allDrawn   all numbers drawn so far
     * @param winningPageIds page IDs of MY pages that now have a complete row
     */
    default void onNumberDrawn(int number, List<Integer> allDrawn, List<Integer> winningPageIds) {}

    /** GAME_ENDED — a winner was confirmed. */
    default void onGameEnded(String winnerId, String winnerName) {}

    /** GAME_ENDED_SERVER — server ended game with no winner. */
    default void onGameEndedByServer(String reason) {}

    /** GAME_CANCELLED — game was cancelled, refunds issued. */
    default void onGameCancelled(String reason, long totalRefunded) {}

    /** GAME_PAUSED — draw timer stopped. */
    default void onGamePaused() {}

    /** GAME_RESUMED — draw timer restarted. */
    default void onGameResumed(int drawIntervalMs) {}

    /** ROOM_RESET — new round started. */
    default void onRoomReset(long prizePerWinner, int winnerCount) {}

    // ── Win ───────────────────────────────────────────────────────────

    /** CLAIM_RECEIVED — server received a win claim from someone. */
    default void onClaimReceived(String playerId, String playerName, int pageId) {}

    /** WIN_CONFIRMED — a claim was accepted. */
    default void onWinConfirmed(String playerId, String playerName, int pageId) {}

    /** WIN_REJECTED — a claim was rejected. */
    default void onWinRejected(String playerId, int pageId) {}

    // ── Pages ─────────────────────────────────────────────────────────

    /** PAGES_ASSIGNED — new pages issued to me. */
    default void onPagesAssigned(List<LotoPage> pages) {}

    /** PAGE_CHANGED — one of my pages was shuffled. */
    default void onPageChanged(int pageId, LotoPage page) {}

    /** PLAYER_PAGES — pages of another player (response to GET_PAGES). */
    default void onPlayerPages(String playerId, String playerName, List<LotoPage> pages) {}

    // ── Balance ───────────────────────────────────────────────────────

    /** BALANCE_UPDATE — a transaction changed my balance. */
    default void onBalanceUpdate(long newBalance, String txType, long amount, String note) {}

    /** WALLET_HISTORY — full transaction ledger received. */
    default void onWalletHistory(long balance, org.json.JSONArray transactions) {}

    // ── Settings change ───────────────────────────────────────────────

    /** DRAW_INTERVAL_CHANGED */
    default void onDrawIntervalChanged(int ms) {}

    /** PRICE_PER_PAGE_CHANGED */
    default void onPricePerPageChanged(long newPrice) {}

    /** AUTO_START_SCHEDULED (0 = cancelled) */
    default void onAutoStartScheduled(int delayMs) {}

    /** AUTO_RESET_SCHEDULED (0 = cancelled) */
    default void onAutoResetScheduled(int delayMs) {}

    // ── Moderation ────────────────────────────────────────────────────

    default void onKicked(String reason) {}
    default void onBanned(String reason) {}
    default void onError(String code, String detail) {}
}

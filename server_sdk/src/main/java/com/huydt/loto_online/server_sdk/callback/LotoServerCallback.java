package com.huydt.loto_online.server_sdk.callback;

import com.huydt.loto_online.server_sdk.model.LotoPage;
import com.huydt.loto_online.server_sdk.model.LotoPlayer;
import com.huydt.socket_base.server.model.Player;

import java.util.List;

/**
 * Callback interface for Loto game events.
 *
 * <p>Implement this to hook into the game lifecycle from your application layer.
 * All methods have empty default implementations — override only what you need.
 *
 * <pre>
 * server.getEventBus().on(EventType.PLAYER_JOINED, e -> log(e.getPlayer().getName()));
 * // OR for loto-specific events:
 * LotoServer loto = new LotoServer.Builder()
 *     .callback(new LotoServerCallback() {
 *         public void onNumberDrawn(String roomId, int number, List&lt;Integer&gt; all) {
 *             log("Drew: " + number);
 *         }
 *     }).build();
 * </pre>
 */
public interface LotoServerCallback {

    // ── Game flow ──────────────────────────────────────────────────────

    default void onVoteUpdate(String roomId, int votes, int needed) {}
    default void onGameStarting(String roomId) {}
    default void onNumberDrawn(String roomId, int number, List<Integer> allDrawn) {}
    default void onGameEnded(String roomId, LotoPlayer winner, long prize) {}
    default void onGameEndedByServer(String roomId, String reason) {}
    default void onGameCancelled(String roomId, String reason, long totalRefunded) {}
    default void onGamePaused(String roomId) {}
    default void onGameResumed(String roomId) {}
    default void onRoomReset(String roomId, long prizePerWinner, int winnerCount) {}

    // ── Win ───────────────────────────────────────────────────────────

    default void onClaimReceived(String roomId, LotoPlayer player, int pageId) {}
    default void onWinConfirmed(String roomId, LotoPlayer player, int pageId, long prize) {}
    default void onWinRejected(String roomId, LotoPlayer player, int pageId) {}
    default void onJackpotPaid(String roomId, List<String> winnerIds, long prizeEach) {}

    // ── Player ────────────────────────────────────────────────────────

    default void onPagesBought(String roomId, LotoPlayer player, List<LotoPage> newPages) {}
    default void onInsufficientBalance(String roomId, LotoPlayer player, long needed, long has) {}
    default void onTopUp(String roomId, LotoPlayer player, long amount) {}
    default void onPlayerBanned(String roomId, String playerId, String reason) {}
    default void onPlayerUnbanned(String roomId, String name) {}

    // ── Timers ────────────────────────────────────────────────────────

    default void onAutoStartScheduled(String roomId, int delayMs) {}
    default void onAutoResetScheduled(String roomId, int delayMs) {}
    default void onDrawIntervalChanged(String roomId, int oldMs, int newMs) {}
    default void onPricePerPageChanged(String roomId, long oldPrice, long newPrice) {}
    default void onAutoStartMsChanged(String roomId, int oldMs, int newMs) {}
    default void onAutoResetDelayChanged(String roomId, int oldMs, int newMs) {}
}

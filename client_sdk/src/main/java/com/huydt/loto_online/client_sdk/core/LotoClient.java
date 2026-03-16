package com.huydt.loto_online.client_sdk.core;

import com.huydt.loto_online.client_sdk.callback.LotoClientCallback;
import com.huydt.loto_online.client_sdk.model.LotoPage;
import com.huydt.loto_online.client_sdk.model.LotoPlayerInfo;
import com.huydt.loto_online.client_sdk.model.LotoRoomInfo;
import com.huydt.loto_online.client_sdk.protocol.LotoOutboundMsg;
import com.huydt.socket_base.client.core.ClientConfig;
import com.huydt.socket_base.client.core.ClientSession;
import com.huydt.socket_base.client.core.SocketBaseClient;
import com.huydt.socket_base.client.event.ClientEvent;
import com.huydt.socket_base.client.event.ClientEventType;
import com.huydt.socket_base.client.protocol.InboundMsg;
import org.json.JSONArray;
import org.json.JSONObject;

import java.util.ArrayList;
import java.util.List;

/**
 * Loto Online client — extends {@link SocketBaseClient} with full game support.
 *
 * <h3>Minimal usage</h3>
 * <pre>
 * LotoClient client = new LotoClient.Builder()
 *     .host("localhost").port(9001).name("Alice")
 *     .roomId("default")
 *     .callback(new LotoClientCallback() {
 *         public void onNumberDrawn(int n, List&lt;Integer&gt; all, List&lt;Integer&gt; winning) {
 *             System.out.println("Drew: " + n);
 *             if (!winning.isEmpty()) client.claimWin(winning.get(0));
 *         }
 *     })
 *     .build();
 *
 * client.connect();
 * </pre>
 *
 * <h3>Convenience game methods</h3>
 * <pre>
 * client.buyPage(2);                // buy 2 pages
 * client.voteStart();               // vote to start
 * client.claimWin(pageId);         // claim winning page
 * client.changePage(pageId);       // re-roll a page
 * client.getWallet();              // request transaction history
 * client.getPages(playerId);       // request another player's pages
 * </pre>
 */
public class LotoClient extends SocketBaseClient {

    private final LotoSession          lotoSession;
    private final LotoClientCallback   callback;
    private       String               currentRoomId;

    // ── Constructor ───────────────────────────────────────────────────

    protected LotoClient(Builder b) {
        super(b);
        this.lotoSession   = new LotoSession(getSession()); // wrap base session
        this.callback      = b.callback != null ? b.callback : new LotoClientCallback() {};
        this.currentRoomId = b.roomId;

        registerBaseListeners();
    }

    // ── Base event listeners ──────────────────────────────────────────

    private void registerBaseListeners() {
        on(ClientEventType.CONNECTED, e -> {
            if (callback != null) callback.onConnected();
        });

        on(ClientEventType.DISCONNECTED, e -> {
            callback.onDisconnected(e.getMessage());
        });

        on(ClientEventType.WELCOME, e -> {
            lotoSession.updateFromWelcome(e.getPayload());
            // Track room id for convenience methods
            if (lotoSession.getRoomId() != null) currentRoomId = lotoSession.getRoomId();
            callback.onWelcome(lotoSession.getPlayerId(), lotoSession.getBalance());
        });

        on(ClientEventType.RECONNECTED, e -> {
            lotoSession.updateFromWelcome(e.getPayload());
            if (lotoSession.getRoomId() != null) currentRoomId = lotoSession.getRoomId();
            callback.onReconnected(lotoSession.getPlayerId());
        });

        on(ClientEventType.ROOM_SNAPSHOT, e -> {
            JSONObject roomJson = e.getPayload().optJSONObject("room");
            if (roomJson != null) lotoSession.onRoomSnapshot(roomJson);
            LotoRoomInfo room = lotoSession.getLotoRoom();
            if (room != null) {
                currentRoomId = room.id;
                lotoSession.onRoomUpdate(room.jackpot, room.pricePerPage);
                callback.onRoomSnapshot(room);
            }
        });

        on(ClientEventType.PLAYER_JOINED, e -> {
            JSONObject pj = e.getPayload().optJSONObject("player");
            if (pj != null) {
                lotoSession.onPlayerJoined(pj);
                callback.onPlayerJoined(new LotoPlayerInfo(pj));
            }
        });

        on(ClientEventType.PLAYER_LEFT, e -> {
            String pid     = e.getPayload().optString("playerId");
            boolean perm   = e.getPayload().optBoolean("permanent", false);
            lotoSession.onPlayerLeft(pid, perm);
            if (perm) callback.onPlayerLeft(pid);
            else      callback.onPlayerDisconnected(pid);
        });

        on(ClientEventType.PLAYER_RECONNECTED, e -> {
            JSONObject pj = e.getPayload().optJSONObject("player");
            if (pj != null) {
                lotoSession.onPlayerReconnected(pj);
                callback.onPlayerReconnected(new LotoPlayerInfo(pj));
            }
        });

        on(ClientEventType.PLAYER_UPDATE, e -> {
            JSONObject pj = e.getPayload().optJSONObject("player");
            if (pj != null) {
                lotoSession.onPlayerUpdate(pj);
                callback.onPlayerUpdate(new LotoPlayerInfo(pj));
            }
        });

        on(ClientEventType.KICKED, e -> {
            callback.onKicked(e.getPayload().optString("reason"));
        });

        on(ClientEventType.BANNED, e -> {
            callback.onBanned(e.getPayload().optString("reason"));
        });

        on(ClientEventType.ERROR, e -> {
            JSONObject p = e.getPayload();
            callback.onError(
                    p != null ? p.optString("code", "ERROR") : "ERROR",
                    p != null ? p.optString("detail", e.getMessage()) : e.getMessage());
        });
    }

    // ── CUSTOM_MSG dispatch ───────────────────────────────────────────

    @Override
    protected void dispatchCustom(InboundMsg msg, JSONObject payload) {
        String tag  = payload.optString("tag",  null);
        JSONObject data = payload.optJSONObject("data");
        if (data == null) data = new JSONObject();

        if (tag == null) return;

        switch (tag) {

            // ── Pages ─────────────────────────────────────────────────
            case "PAGES_ASSIGNED": {
                JSONArray arr = data.optJSONArray("pages");
                lotoSession.onPagesAssigned(arr);
                callback.onPagesAssigned(lotoSession.getPages());
                break;
            }

            case "PAGE_CHANGED": {
                int pageId        = data.optInt("pageId", -1);
                JSONArray newGrid = data.optJSONArray("page");
                lotoSession.onPageChanged(pageId, newGrid);
                LotoPage page = lotoSession.getPage(pageId);
                if (page != null) callback.onPageChanged(pageId, page);
                break;
            }

            case "PLAYER_PAGES": {
                String pid  = data.optString("playerId");
                String name = data.optString("playerName");
                JSONArray arr = data.optJSONArray("pages");
                List<LotoPage> pages = new ArrayList<>();
                if (arr != null) {
                    for (int i = 0; i < arr.length(); i++) {
                        JSONObject pg = arr.optJSONObject(i);
                        if (pg != null) pages.add(LotoPage.fromJson(pg));
                    }
                }
                callback.onPlayerPages(pid, name, pages);
                break;
            }

            // ── Game flow ─────────────────────────────────────────────
            case "NUMBER_DRAWN": {
                int number = data.optInt("number", -1);
                if (number < 1) break;
                List<Integer> winning = lotoSession.onNumberDrawn(number);
                callback.onNumberDrawn(number, lotoSession.getDrawnNumbers(), winning);
                break;
            }

            case "VOTE_UPDATE": {
                int count  = data.optInt("voteCount",  0);
                int needed = data.optInt("voteNeeded", 1);
                lotoSession.onVoteUpdate(count, needed);
                callback.onVoteUpdate(count, needed);
                break;
            }

            case "GAME_STARTING": {
                int interval = data.optInt("drawIntervalMs", 5000);
                lotoSession.onGameStarting(interval);
                callback.onGameStarting(interval);
                break;
            }

            case "GAME_ENDED": {
                lotoSession.onGameEnded();
                callback.onGameEnded(data.optString("winnerId"), data.optString("winnerName"));
                break;
            }

            case "GAME_ENDED_SERVER": {
                lotoSession.onGameEnded();
                callback.onGameEndedByServer(data.optString("reason"));
                break;
            }

            case "GAME_CANCELLED": {
                lotoSession.onGameCancelled();
                callback.onGameCancelled(data.optString("reason"), data.optLong("totalRefunded", 0));
                break;
            }

            case "GAME_PAUSED": {
                lotoSession.onGamePaused();
                callback.onGamePaused();
                break;
            }

            case "GAME_RESUMED": {
                int interval = data.optInt("drawIntervalMs", 5000);
                lotoSession.onGameResumed(interval);
                callback.onGameResumed(interval);
                break;
            }

            case "ROOM_RESET": {
                lotoSession.onRoomReset();
                callback.onRoomReset(data.optLong("prizePerWinner", 0), data.optInt("winnerCount", 0));
                break;
            }

            // ── Win ───────────────────────────────────────────────────
            case "CLAIM_RECEIVED":
                callback.onClaimReceived(data.optString("playerId"),
                        data.optString("playerName"), data.optInt("pageId", -1));
                break;

            case "WIN_CONFIRMED":
                lotoSession.onWinConfirmed();
                callback.onWinConfirmed(data.optString("playerId"),
                        data.optString("playerName"), data.optInt("pageId", -1));
                break;

            case "WIN_REJECTED":
                callback.onWinRejected(data.optString("playerId"), data.optInt("pageId", -1));
                break;

            // ── Balance ───────────────────────────────────────────────
            case "BALANCE_UPDATE": {
                long newBalance = data.optLong("balanceAfter", data.optLong("balance", 0));
                lotoSession.onBalanceUpdate(newBalance);
                callback.onBalanceUpdate(newBalance,
                        data.optString("type"), data.optLong("amount", 0), data.optString("note"));
                break;
            }

            case "WALLET_HISTORY":
                callback.onWalletHistory(data.optLong("balance", 0),
                        data.optJSONArray("transactions"));
                break;

            // ── Settings ──────────────────────────────────────────────
            case "DRAW_INTERVAL_CHANGED": {
                int ms = data.optInt("drawIntervalMs", 5000);
                lotoSession.onDrawIntervalChanged(ms);
                callback.onDrawIntervalChanged(ms);
                break;
            }

            case "PRICE_PER_PAGE_CHANGED": {
                long price = data.optLong("pricePerPage", 0);
                lotoSession.onPriceChanged(price);
                callback.onPricePerPageChanged(price);
                break;
            }

            case "AUTO_START_SCHEDULED": {
                int ms = data.optInt("delayMs", 0);
                lotoSession.onAutoStartScheduled(ms);
                callback.onAutoStartScheduled(ms);
                break;
            }

            case "AUTO_RESET_SCHEDULED": {
                int ms = data.optInt("delayMs", 0);
                lotoSession.onAutoResetScheduled(ms);
                callback.onAutoResetScheduled(ms);
                break;
            }

            default:
                // Unknown tag — forward to generic CUSTOM_MSG event bus listeners
                super.dispatchCustom(msg, payload);
        }
    }

    // ── Convenience game methods ──────────────────────────────────────

    /** Buy {@code count} pages in the current room. */
    public boolean buyPage(int count) {
        return send(LotoOutboundMsg.buyPage(requireRoomId(), count));
    }

    /** Re-roll (shuffle) one of my pages. */
    public boolean changePage(int pageId) {
        return send(LotoOutboundMsg.changePage(requireRoomId(), pageId));
    }

    /** Vote to start the game. */
    public boolean voteStart() {
        return send(LotoOutboundMsg.voteStart(requireRoomId()));
    }

    /** Claim that I have a winning row on page {@code pageId}. */
    public boolean claimWin(int pageId) {
        return send(LotoOutboundMsg.claimWin(requireRoomId(), pageId));
    }

    /** Request my page grids from the server. */
    public boolean getMyPages() {
        return send(LotoOutboundMsg.getPages(requireRoomId(), null));
    }

    /** Request another player's page grids. */
    public boolean getPages(String playerId) {
        return send(LotoOutboundMsg.getPages(requireRoomId(), playerId));
    }

    /** Request my wallet transaction history. */
    public boolean getWallet() {
        return send(LotoOutboundMsg.getWallet(requireRoomId()));
    }

    // ── Admin convenience methods ─────────────────────────────────────

    public boolean gameStart()                         { return send(LotoOutboundMsg.gameStart(requireRoomId())); }
    public boolean gameEnd(String reason)              { return send(LotoOutboundMsg.gameEnd(requireRoomId(), reason)); }
    public boolean gameCancel(String reason)           { return send(LotoOutboundMsg.gameCancel(requireRoomId(), reason)); }
    public boolean gamePause()                         { return send(LotoOutboundMsg.gamePause(requireRoomId())); }
    public boolean gameResume()                        { return send(LotoOutboundMsg.gameResume(requireRoomId())); }
    public boolean gameReset()                         { return send(LotoOutboundMsg.gameReset(requireRoomId())); }
    public boolean confirmWin(String pid, int pageId)  { return send(LotoOutboundMsg.confirmWin(requireRoomId(), pid, pageId)); }
    public boolean rejectWin(String pid, int pageId)   { return send(LotoOutboundMsg.rejectWin(requireRoomId(), pid, pageId)); }
    public boolean topUp(String pid, long amt, String note) { return send(LotoOutboundMsg.topUp(requireRoomId(), pid, amt, note)); }
    public boolean setDrawInterval(int ms)             { return send(LotoOutboundMsg.setDrawInterval(requireRoomId(), ms)); }
    public boolean setPricePerPage(long price)         { return send(LotoOutboundMsg.setPricePerPage(requireRoomId(), price)); }
    public boolean setAutoReset(int ms)                { return send(LotoOutboundMsg.setAutoReset(requireRoomId(), ms)); }
    public boolean setAutoStart(int ms)                { return send(LotoOutboundMsg.setAutoStart(requireRoomId(), ms)); }
    public boolean botAdd(String name, long bal, int maxPages) { return send(LotoOutboundMsg.botAdd(requireRoomId(), name, bal, maxPages)); }
    public boolean botRemove(String name)              { return send(LotoOutboundMsg.botRemove(requireRoomId(), name)); }

    // ── Accessors ─────────────────────────────────────────────────────

    public LotoSession getLotoSession()   { return lotoSession; }
    public String      getCurrentRoomId() { return currentRoomId; }

    // ── Internal ──────────────────────────────────────────────────────

    private String requireRoomId() {
        String rid = lotoSession.getRoomId() != null ? lotoSession.getRoomId() : currentRoomId;
        if (rid == null) throw new IllegalStateException("Not in a room — join a room first");
        return rid;
    }

    // ── Builder ───────────────────────────────────────────────────────

    public static class Builder extends SocketBaseClient.Builder {

        private LotoClientCallback callback = null;
        private String             roomId   = null;

        /** Loto game event callback. */
        public Builder callback(LotoClientCallback cb) { this.callback = cb; return this; }

        /** Auto-join this room after WELCOME. */
        @Override
        public Builder roomId(String roomId) {
            this.roomId = roomId;
            super.roomId(roomId);
            return this;
        }

        // ── Shorthand overrides (return Builder not SocketBaseClient.Builder) ──

        @Override public Builder host(String h)            { super.host(h);     return this; }
        @Override public Builder port(int p)               { super.port(p);     return this; }
        @Override public Builder useSsl(boolean s)         { super.useSsl(s);   return this; }
        @Override public Builder protocol(ClientConfig.Protocol p) { super.protocol(p); return this; }
        @Override public Builder url(String url)           { super.url(url);    return this; }
        @Override public Builder name(String name)         { super.name(name);  return this; }
        @Override public Builder reconnectToken(String t)  { super.reconnectToken(t); return this; }
        @Override public Builder adminToken(String t)      { super.adminToken(t); return this; }
        @Override public Builder config(ClientConfig c)    { super.config(c);   return this; }

        @Override
        public LotoClient build() { return new LotoClient(this); }
    }
}

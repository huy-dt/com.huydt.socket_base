package com.huydt.loto_online.client_sdk.core;

import com.huydt.loto_online.client_sdk.model.LotoPage;
import com.huydt.loto_online.client_sdk.model.LotoRoomInfo;
import com.huydt.socket_base.client.core.ClientSession;
import com.huydt.socket_base.client.model.PlayerInfo;
import org.json.JSONArray;
import org.json.JSONObject;

import java.util.ArrayList;
import java.util.Collections;
import java.util.List;

/**
 * Holds all Loto game state for this client.
 *
 * <p>Uses <b>composition</b> over the base {@link ClientSession}
 * (which is {@code final}) — wraps it and delegates identity accessors.
 * Created and owned by {@link LotoClient}.
 *
 * <h3>State managed here</h3>
 * <ul>
 *   <li>{@code balance}       — my current wallet balance</li>
 *   <li>{@code pages}         — my loto pages this round</li>
 *   <li>{@code drawnNumbers}  — all numbers drawn so far</li>
 *   <li>{@code gameState}     — WAITING / VOTING / PLAYING / PAUSED / ENDED</li>
 *   <li>{@code jackpot}       — prize pool</li>
 *   <li>{@code pricePerPage}  — cost per page</li>
 *   <li>{@code voteCount / voteNeeded} — vote progress</li>
 *   <li>{@code isPaused}      — draw timer paused</li>
 *   <li>{@code autoStartMs / autoResetMs} — countdown timers</li>
 * </ul>
 */
public class LotoSession {

    // ── Delegate: base session (identity + room tracking) ─────────────
    private final ClientSession base;

    // ── My private state ──────────────────────────────────────────────
    private long             balance        = 0;
    private final List<LotoPage>  pages     = new ArrayList<>();

    // ── Game state ────────────────────────────────────────────────────
    private String           gameState      = "WAITING";
    private boolean          isPaused       = false;
    private long             jackpot        = 0;
    private long             pricePerPage   = 0;
    private long             pendingPrice   = -1;
    private int              drawIntervalMs = 5000;
    private int              voteCount      = 0;
    private int              voteNeeded     = 1;
    private int              autoStartMs    = 0;
    private int              autoResetMs    = 0;
    private final List<Integer> drawnNumbers = new ArrayList<>();

    LotoSession(ClientSession base) {
        this.base = base;
        base.setRoomFactory(LotoRoomInfo::new);
    }

    // ── WELCOME / RECONNECTED ─────────────────────────────────────────

    /**
     * Called on WELCOME/RECONNECTED.
     * Delegates identity + room update to base, then parses loto-specific fields.
     */
    public void updateFromWelcome(JSONObject payload) {
        base.update(payload);   // playerId, token, name, room

        JSONObject player = payload.optJSONObject("player");
        if (player != null) {
            this.balance = player.optLong("balance", balance);
        }

        pages.clear();
        JSONArray pagesArr = payload.optJSONArray("pages");
        if (pagesArr != null) {
            for (int i = 0; i < pagesArr.length(); i++) {
                JSONObject pg = pagesArr.optJSONObject(i);
                if (pg != null) pages.add(LotoPage.fromJson(pg));
            }
        }

        this.gameState  = payload.optString("gameState", gameState);
        this.isPaused   = payload.optBoolean("isPaused",  false);
        this.voteCount  = payload.optInt("voteCount",     0);
        this.voteNeeded = payload.optInt("voteNeeded",    1);

        JSONObject room = payload.optJSONObject("room");
        if (room != null) {
            this.jackpot        = room.optLong("jackpot",         jackpot);
            this.pricePerPage   = room.optLong("pricePerPage",    pricePerPage);
            this.pendingPrice   = room.optLong("pendingPrice",    -1);
            this.drawIntervalMs = room.optInt("drawIntervalMs",   drawIntervalMs);
            this.autoResetMs    = room.optInt("autoResetDelayMs", 0);
            this.autoStartMs    = room.optInt("autoStartMs",      0);
            this.gameState      = room.optString("state",          gameState);
        }

        drawnNumbers.clear();
        JSONArray drawn = payload.optJSONArray("drawnNumbers");
        if (drawn != null) {
            for (int i = 0; i < drawn.length(); i++) drawnNumbers.add(drawn.optInt(i));
            for (LotoPage p : pages) p.markAll(drawnNumbers);
        }
    }

    // ── Delegate room/player updates to base ──────────────────────────

    public void onRoomSnapshot(JSONObject roomJson) { base.onRoomSnapshot(roomJson); }
    public void onPlayerJoined(JSONObject pj)       { base.onPlayerJoined(pj); }
    public void onPlayerLeft(String id, boolean permanent) { base.onPlayerLeft(id, permanent); }
    public void onPlayerReconnected(JSONObject pj)  { base.onPlayerReconnected(pj); }
    public void onPlayerUpdate(JSONObject pj)        { base.onPlayerUpdate(pj); }
    public void onRoomUpdate(JSONObject rj)          { base.onRoomUpdate(rj); }

    // ── Loto incremental updates ──────────────────────────────────────

    /** NUMBER_DRAWN — mark pages, return IDs of pages now having a winning row. */
    public List<Integer> onNumberDrawn(int number) {
        drawnNumbers.add(number);
        List<Integer> winning = new ArrayList<>();
        for (LotoPage p : pages) {
            p.mark(number);
            if (p.hasWinningRow()) winning.add(p.id);
        }
        return Collections.unmodifiableList(winning);
    }

    /** PAGES_ASSIGNED — add new pages. */
    public void onPagesAssigned(JSONArray pagesJson) {
        if (pagesJson == null) return;
        for (int i = 0; i < pagesJson.length(); i++) {
            JSONObject pg = pagesJson.optJSONObject(i);
            if (pg != null) {
                LotoPage page = LotoPage.fromJson(pg);
                page.markAll(drawnNumbers);
                pages.add(page);
            }
        }
    }

    /** PAGE_CHANGED — re-roll one page grid. */
    public void onPageChanged(int pageId, JSONArray newGrid) {
        for (LotoPage p : pages) {
            if (p.id == pageId) { p.updateGrid(newGrid); p.markAll(drawnNumbers); return; }
        }
    }

    public void onBalanceUpdate(long newBalance)    { this.balance = newBalance; }
    public void onVoteUpdate(int count, int needed) { this.voteCount = count; this.voteNeeded = needed; }

    public void onGameStarting(int ms) {
        this.gameState = "PLAYING"; this.isPaused = false; this.drawIntervalMs = ms;
    }

    public void onGameEnded()     { this.gameState = "ENDED"; }
    public void onGamePaused()    { this.isPaused = true; }
    public void onGameResumed(int ms) { this.isPaused = false; this.drawIntervalMs = ms; }
    public void onGameCancelled() { this.gameState = "ENDED"; }
    public void onWinConfirmed()  { this.gameState = "ENDED"; }

    public void onRoomReset() {
        pages.clear(); drawnNumbers.clear();
        gameState = "WAITING"; isPaused = false; voteCount = 0; jackpot = 0;
    }

    public void onDrawIntervalChanged(int ms)  { this.drawIntervalMs = ms; }
    public void onPriceChanged(long price)     { this.pricePerPage = price; }
    public void onAutoStartScheduled(int ms)   { this.autoStartMs = ms; }
    public void onAutoResetScheduled(int ms)   { this.autoResetMs = ms; }

    public void onRoomUpdate(long jackpot, long pricePerPage) {
        this.jackpot = jackpot; this.pricePerPage = pricePerPage;
    }

    // ── Accessors: loto state ─────────────────────────────────────────

    public long           getBalance()       { return balance; }
    public List<LotoPage> getPages()         { return Collections.unmodifiableList(pages); }
    public List<Integer>  getDrawnNumbers()  { return Collections.unmodifiableList(drawnNumbers); }
    public String         getGameState()     { return gameState; }
    public boolean        isPaused()         { return isPaused; }
    public long           getJackpot()       { return jackpot; }
    public long           getPricePerPage()  { return pricePerPage; }
    public long           getPendingPrice()  { return pendingPrice; }
    public int            getDrawIntervalMs(){ return drawIntervalMs; }
    public int            getVoteCount()     { return voteCount; }
    public int            getVoteNeeded()    { return voteNeeded; }
    public int            getAutoStartMs()   { return autoStartMs; }
    public int            getAutoResetMs()   { return autoResetMs; }

    public LotoPage getPage(int pageId) {
        return pages.stream().filter(p -> p.id == pageId).findFirst().orElse(null);
    }

    public LotoRoomInfo getLotoRoom() {
        return (base.getCurrentRoom() instanceof LotoRoomInfo)
                ? (LotoRoomInfo) base.getCurrentRoom() : null;
    }

    // ── Accessors: delegate to base (identity + room) ─────────────────

    public String  getPlayerId()   { return base.getPlayerId(); }
    public String  getToken()      { return base.getToken(); }
    public String  getName()       { return base.getName(); }
    public String  getRoomId()     { return base.getRoomId(); }
    public boolean isAdmin()       { return base.isAdmin(); }
    public boolean isLoggedIn()    { return base.isLoggedIn(); }
    public boolean isInRoom()      { return base.isInRoom(); }

    public PlayerInfo getPlayer(String id) { return base.getPlayer(id); }

    /** Access the underlying base session (for passing to listeners that expect ClientSession). */
    public ClientSession getBaseSession() { return base; }

    @Override
    public String toString() {
        return "LotoSession{id=" + getPlayerId() + ", name=" + getName()
                + ", balance=" + balance + ", pages=" + pages.size()
                + ", state=" + gameState + ", drawn=" + drawnNumbers.size() + "/90}";
    }
}

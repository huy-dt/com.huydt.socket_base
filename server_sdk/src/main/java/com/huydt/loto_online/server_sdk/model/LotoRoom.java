package com.huydt.loto_online.server_sdk.model;

import com.huydt.socket_base.server.model.Room;
import org.json.JSONArray;
import org.json.JSONObject;

import java.util.ArrayList;
import java.util.Collections;
import java.util.List;

/**
 * Loto game room — extends socket_base {@link Room}.
 *
 * <p>Adds loto-specific game state:
 * <ul>
 *   <li>{@code jackpot}       — accumulated prize pool</li>
 *   <li>{@code drawnNumbers}  — numbers drawn so far this round</li>
 *   <li>{@code winnerIds}     — player IDs confirmed as winners</li>
 *   <li>{@code pricePerPage}  — current cost to buy one page</li>
 *   <li>{@code drawIntervalMs}— ms between each number draw</li>
 * </ul>
 *
 * <p>RoomState mapping (socket_base → loto):
 * <pre>
 *   WAITING  → waiting for players + page buying
 *   STARTING → vote countdown in progress
 *   PLAYING  → numbers being drawn
 *   PAUSED   → draw timer stopped
 *   ENDED    → winner confirmed or all 90 numbers drawn
 * </pre>
 */
public class LotoRoom extends Room {

    private long         jackpot       = 0;
    private long         pricePerPage;
    private long         pendingPricePerPage = -1; // applied next round
    private int          drawIntervalMs;
    private int          autoResetDelayMs;
    private int          autoStartMs;
    private final List<Integer> drawnNumbers = new ArrayList<>();
    private final List<String>  winnerIds    = new ArrayList<>();

    public LotoRoom(String id, String name, int maxPlayers,
                    long pricePerPage, int drawIntervalMs,
                    int autoResetDelayMs, int autoStartMs) {
        super(id, name, maxPlayers);
        this.pricePerPage     = pricePerPage;
        this.drawIntervalMs   = drawIntervalMs;
        this.autoResetDelayMs = autoResetDelayMs;
        this.autoStartMs      = autoStartMs;
    }

    // ── Accessors ─────────────────────────────────────────────────

    public long         getJackpot()          { return jackpot; }
    public long         getPricePerPage()      { return pricePerPage; }
    public long         getPendingPricePerPage() { return pendingPricePerPage; }
    public int          getDrawIntervalMs()    { return drawIntervalMs; }
    public int          getAutoResetDelayMs()  { return autoResetDelayMs; }
    public int          getAutoStartMs()       { return autoStartMs; }
    public List<Integer> getDrawnNumbers()     { return Collections.unmodifiableList(drawnNumbers); }
    public List<String>  getWinnerIds()        { return Collections.unmodifiableList(winnerIds); }

    // ── Mutators ──────────────────────────────────────────────────

    public void addToJackpot(long amount)        { jackpot += amount; }
    public void resetJackpot()                   { jackpot = 0; }
    public void setPricePerPage(long price)      { pricePerPage = price; }
    public void setPendingPricePerPage(long p)   { pendingPricePerPage = p; }
    public void setDrawIntervalMs(int ms)        { drawIntervalMs = ms; }
    public void setAutoResetDelayMs(int ms)      { autoResetDelayMs = ms; }
    public void setAutoStartMs(int ms)           { autoStartMs = ms; }

    public void addDrawnNumber(int n)            { drawnNumbers.add(n); }
    public void clearDrawnNumbers()              { drawnNumbers.clear(); }
    public void addWinnerId(String id)           { if (!winnerIds.contains(id)) winnerIds.add(id); }
    public void clearWinnerIds()                 { winnerIds.clear(); }

    public void applyPendingPrice() {
        if (pendingPricePerPage >= 0) {
            pricePerPage        = pendingPricePerPage;
            pendingPricePerPage = -1;
        }
    }

    // ── Serialization ─────────────────────────────────────────────

    @Override
    public JSONObject toJson() {
        JSONObject j = super.toJson(); // id, name, state, players[], maxPlayers...
        j.put("jackpot",        jackpot);
        j.put("pricePerPage",   pricePerPage);
        j.put("pendingPrice",   pendingPricePerPage);
        j.put("drawIntervalMs", drawIntervalMs);
        j.put("autoResetDelayMs", autoResetDelayMs);
        j.put("autoStartMs",    autoStartMs);
        j.put("drawnCount",     drawnNumbers.size());

        JSONArray winners = new JSONArray();
        winnerIds.forEach(winners::put);
        j.put("winnerIds", winners);
        return j;
    }

    @Override
    public void fromJson(JSONObject j) {
        super.fromJson(j);
        jackpot           = j.optLong("jackpot", 0);
        pricePerPage      = j.optLong("pricePerPage", pricePerPage);
        pendingPricePerPage = j.optLong("pendingPrice", -1);
        drawIntervalMs    = j.optInt("drawIntervalMs", drawIntervalMs);
        autoResetDelayMs  = j.optInt("autoResetDelayMs", autoResetDelayMs);
        autoStartMs       = j.optInt("autoStartMs", autoStartMs);
    }
}

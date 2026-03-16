package com.huydt.loto_online.client_sdk.model;

import com.huydt.socket_base.client.model.PlayerInfo;
import com.huydt.socket_base.client.model.RoomInfo;
import org.json.JSONArray;
import org.json.JSONObject;

import java.util.ArrayList;
import java.util.Collections;
import java.util.List;

/**
 * Client-side snapshot of a Loto game room.
 *
 * <p>Extends base {@link RoomInfo} with loto-specific fields:
 * <ul>
 *   <li>{@code jackpot}        — accumulated prize pool</li>
 *   <li>{@code pricePerPage}   — cost per page this round</li>
 *   <li>{@code pendingPrice}   — price that will apply next round (-1 = none)</li>
 *   <li>{@code drawIntervalMs} — ms between draws</li>
 *   <li>{@code drawnCount}     — how many numbers have been drawn</li>
 *   <li>{@code winnerIds}      — player IDs confirmed as winners</li>
 *   <li>{@code autoResetDelayMs / autoStartMs} — timer config</li>
 * </ul>
 *
 * <p>Register via session:
 * <pre>
 * session.setRoomFactory(LotoRoomInfo::new);
 * </pre>
 */
public class LotoRoomInfo extends RoomInfo {

    public long         jackpot;
    public long         pricePerPage;
    public long         pendingPrice  = -1;
    public int          drawIntervalMs;
    public int          autoResetDelayMs;
    public int          autoStartMs;
    public int          drawnCount;
    public List<String> winnerIds     = new ArrayList<>();

    @Override
    public void mergeRoom(JSONObject j) {
        super.mergeRoom(j);
        if (j.has("jackpot"))          this.jackpot          = j.optLong("jackpot", 0);
        if (j.has("pricePerPage"))     this.pricePerPage     = j.optLong("pricePerPage", 0);
        if (j.has("pendingPrice"))     this.pendingPrice     = j.optLong("pendingPrice", -1);
        if (j.has("drawIntervalMs"))   this.drawIntervalMs   = j.optInt("drawIntervalMs", 5000);
        if (j.has("autoResetDelayMs")) this.autoResetDelayMs = j.optInt("autoResetDelayMs", 0);
        if (j.has("autoStartMs"))      this.autoStartMs      = j.optInt("autoStartMs", 0);
        if (j.has("drawnCount"))       this.drawnCount       = j.optInt("drawnCount", 0);

        JSONArray winners = j.optJSONArray("winnerIds");
        if (winners != null) {
            winnerIds.clear();
            for (int i = 0; i < winners.length(); i++) winnerIds.add(winners.optString(i));
        }
    }

    @Override
    protected PlayerInfo createPlayer(JSONObject j) {
        return new LotoPlayerInfo(j);
    }

    @Override
    public String toString() {
        return "LotoRoom{id=" + id + ", name=" + name + ", state=" + state
                + ", jackpot=" + jackpot + ", price=" + pricePerPage
                + ", drawn=" + drawnCount + "/90, players=" + players.size() + "}";
    }
}

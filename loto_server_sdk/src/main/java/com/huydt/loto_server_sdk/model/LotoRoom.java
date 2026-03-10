package com.huydt.loto_server_sdk.model;

import com.huydt.socket_base.server.model.Room;
import org.json.JSONArray;
import org.json.JSONObject;

import java.util.ArrayList;
import java.util.List;

/**
 * Loto-specific room.
 *
 * Extra fields:
 *   jackpot       — accumulated prize pool
 *   pricePerPage  — cost for a player to buy one page/ticket
 *   timeAutoReset — ms after round ends before auto-reset to WAITING (0 = disabled)
 *   timeAutoStart — ms after players ready before auto-start (0 = disabled)
 *   round         — current round number
 *   drawnNumbers  — numbers drawn so far this round
 */
public class LotoRoom extends Room {

    public long jackpot       = 0;
    public long pricePerPage  = 1_000;
    public long timeAutoReset = 0;
    public long timeAutoStart = 0;
    public int  round         = 0;
    public final List<Integer> drawnNumbers = new ArrayList<>();

    /** Auto-generated id — use when creating via admin command. */
    public LotoRoom(String name, int maxPlayers) {
        super(name, maxPlayers);
    }

    /** Explicit id — required by RoomManager.newRoom(). */
    public LotoRoom(String id, String name, int maxPlayers) {
        super(id, name, maxPlayers);
    }

    @Override
    public JSONObject toJson() {
        JSONObject j = super.toJson();
        j.put("jackpot",       jackpot);
        j.put("pricePerPage",  pricePerPage);
        j.put("timeAutoReset", timeAutoReset);
        j.put("timeAutoStart", timeAutoStart);
        j.put("round",         round);
        j.put("drawnNumbers",  new JSONArray(drawnNumbers));
        return j;
    }

    @Override
    public void fromJson(JSONObject j) {
        super.fromJson(j);
        this.jackpot       = j.optLong("jackpot",       0);
        this.pricePerPage  = j.optLong("pricePerPage",  1_000);
        this.timeAutoReset = j.optLong("timeAutoReset", 0);
        this.timeAutoStart = j.optLong("timeAutoStart", 0);
        this.round         = j.optInt("round",          0);

        drawnNumbers.clear();
        JSONArray arr = j.optJSONArray("drawnNumbers");
        if (arr != null) {
            for (int i = 0; i < arr.length(); i++) drawnNumbers.add(arr.optInt(i));
        }
    }
}

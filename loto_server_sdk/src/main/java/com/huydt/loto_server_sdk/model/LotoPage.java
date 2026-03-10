package com.huydt.loto_server_sdk.model;

import org.json.JSONObject;

/**
 * Represents a single Loto ticket page owned by a player.
 * TODO: add numbers grid, checked state, etc.
 */
public class LotoPage {

    public final String playerId;

    public LotoPage(String playerId) {
        this.playerId = playerId;
    }

    public JSONObject toJson() {
        JSONObject j = new JSONObject();
        j.put("playerId", playerId);
        return j;
    }

    public static LotoPage fromJson(JSONObject j) {
        return new LotoPage(j.optString("playerId", ""));
    }
}

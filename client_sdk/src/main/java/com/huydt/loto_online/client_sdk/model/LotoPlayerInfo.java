package com.huydt.loto_online.client_sdk.model;

import com.huydt.socket_base.client.model.PlayerInfo;
import org.json.JSONObject;

/**
 * Client-side snapshot of a Loto player.
 *
 * <p>Used inside {@link LotoRoomInfo#players} map.
 * Only fields present in the incoming JSON are updated.
 *
 * <p>Public fields:
 * <ul>
 *   <li>{@code pageCount}  — number of pages this player owns this round</li>
 *   <li>{@code isBot}      — whether this is a bot</li>
 * </ul>
 * Note: {@code balance} is private — it's only sent to the player themselves
 * in WELCOME. Other players only see {@code pageCount}.
 */
public class LotoPlayerInfo extends PlayerInfo {

    public int     pageCount;
    public boolean isBot;

    public LotoPlayerInfo(JSONObject j) {
        super(j);
    }

    @Override
    public void merge(JSONObject j) {
        super.merge(j);
        if (j.has("pageCount")) this.pageCount = j.optInt("pageCount", 0);
        if (j.has("isBot"))     this.isBot     = j.optBoolean("isBot", false);
    }

    @Override
    public String toString() {
        return "LotoPlayer{id=" + id + ", name=" + name
                + ", pages=" + pageCount
                + ", bot=" + isBot
                + ", connected=" + connected + "}";
    }
}

package com.huydt.loto_client_sdk.model;

import com.huydt.socket_base.client.model.PlayerInfo;
import org.json.JSONObject;

/**
 * Client-side mirror of {@code LotoPlayer}.
 *
 * Extra fields:
 *   money     — wallet balance (private, only visible to self via WELCOME/RECONNECTED)
 *   pageCount — number of pages/tickets (public, visible to all room members)
 */
public class LotoPlayerInfo extends PlayerInfo {

    public long money     = 0;
    public int  pageCount = 0;

    public LotoPlayerInfo(JSONObject j) {
        super(j);
    }

    /**
     * Called on every player JSON update:
     *   — ROOM_SNAPSHOT  players[]
     *   — PLAYER_JOINED
     *   — PLAYER_UPDATE  (may be partial — only changed keys present)
     *   — PLAYER_RECONNECTED
     *
     * Always call super first.
     * Only update a field when the key is present — never overwrite with a
     * default when the key is absent (partial updates would lose data).
     */
    @Override
    public void merge(JSONObject j) {
        super.merge(j);  // id, name, roomId, connected, isAdmin
        if (j.has("money"))     this.money     = j.optLong("money",    0);
        if (j.has("pageCount")) this.pageCount = j.optInt("pageCount", 0);
    }
}

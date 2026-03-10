package com.huydt.loto_client_sdk.model;

import com.huydt.socket_base.client.model.PlayerInfo;
import com.huydt.socket_base.client.model.RoomInfo;
import org.json.JSONObject;

/**
 * Client-side mirror of {@code LotoRoom}.
 *
 * Extra fields:
 *   jackpot       — accumulated prize pool
 *   pricePerPage  — cost for a player to buy one page/ticket
 *   timeAutoReset — ms after round ends before auto-reset to WAITING (0 = disabled)
 *   timeAutoStart — ms after players ready before auto-start (0 = disabled)
 */
public class LotoRoomInfo extends RoomInfo {

    public long jackpot       = 0;
    public long pricePerPage  = 0;
    public long timeAutoReset = 0;
    public long timeAutoStart = 0;

    /**
     * Called on every room JSON update:
     *   — ROOM_SNAPSHOT / WELCOME  (full state, before players are rebuilt)
     *   — ROOM_UPDATE              (may be partial — only changed keys present)
     *   — ROOM_STATE_CHANGED       (merged into current room if roomId matches)
     *
     * Always call super first.
     * Only update a field when the key is present.
     */
    @Override
    public void mergeRoom(JSONObject j) {
        super.mergeRoom(j);  // id, name, state, maxPlayers, isPrivate
        if (j.has("jackpot"))       this.jackpot       = j.optLong("jackpot",       0);
        if (j.has("pricePerPage"))  this.pricePerPage  = j.optLong("pricePerPage",  0);
        if (j.has("timeAutoReset")) this.timeAutoReset = j.optLong("timeAutoReset", 0);
        if (j.has("timeAutoStart")) this.timeAutoStart = j.optLong("timeAutoStart", 0);
    }

    /**
     * Called for every player entry in a ROOM_SNAPSHOT players[]
     * and for every PLAYER_JOINED event.
     */
    @Override
    protected PlayerInfo createPlayer(JSONObject j) {
        return new LotoPlayerInfo(j);
    }
}

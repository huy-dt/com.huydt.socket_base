package com.huydt.socket_client_base.model;

import org.json.JSONObject;

/**
 * Client-side snapshot of one player.
 *
 * <p>Updated incrementally — only fields present in the incoming JSON are overwritten.
 * Subclass to add game-specific fields:
 *
 * <pre>
 * public class LotoPlayerInfo extends PlayerInfo {
 *     public long money;
 *     public int  pageCount;
 *
 *     public LotoPlayerInfo(JSONObject j) { super(j); }
 *
 *     &#64;Override
 *     public void merge(JSONObject j) {
 *         super.merge(j);
 *         if (j.has("money"))     this.money     = j.getLong("money");
 *         if (j.has("pageCount")) this.pageCount = j.getInt("pageCount");
 *     }
 * }
 * </pre>
 */
public class PlayerInfo {

    public String    id;
    public String    name;
    public String    roomId;
    public boolean   connected;
    public boolean   isAdmin;
    /** Raw JSON — access game-specific fields via raw.optXxx() until you subclass */
    public JSONObject raw;

    public PlayerInfo(JSONObject j) {
        merge(j);
    }

    /**
     * Merge a partial or full JSON snapshot into this object.
     * Only keys present in {@code j} are updated.
     */
    public void merge(JSONObject j) {
        this.raw = j;
        if (j.has("id"))        this.id        = j.optString("id",       id);
        if (j.has("name"))      this.name      = j.optString("name",     name);
        if (j.has("roomId"))    this.roomId    = j.isNull("roomId") ? null : j.optString("roomId", null);
        if (j.has("connected")) this.connected = j.optBoolean("connected", false);
        if (j.has("isAdmin"))   this.isAdmin   = j.optBoolean("isAdmin",   false);
    }

    @Override
    public String toString() {
        return "Player{id=" + id + ", name=" + name
                + ", connected=" + connected + ", room=" + roomId + "}";
    }
}
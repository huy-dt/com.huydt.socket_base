package com.huydt.socket_client_base.model;

import org.json.JSONArray;
import org.json.JSONObject;

import java.util.Collections;
import java.util.LinkedHashMap;
import java.util.Map;

/**
 * Client-side snapshot of a room and its current player list.
 *
 * <p>Updated incrementally from server events:
 * <ul>
 *   <li>{@code ROOM_SNAPSHOT / WELCOME} — full replace via {@link #replaceFrom(JSONObject)}</li>
 *   <li>{@code PLAYER_JOINED}           — {@link #addPlayer(JSONObject)}</li>
 *   <li>{@code PLAYER_LEFT permanent=false} — {@link #markOffline(String)}</li>
 *   <li>{@code PLAYER_LEFT permanent=true}  — {@link #removePlayer(String)}</li>
 *   <li>{@code PLAYER_RECONNECTED}      — {@link #markOnline(JSONObject)}</li>
 *   <li>{@code PLAYER_UPDATE}           — {@link #mergePlayer(JSONObject)}</li>
 *   <li>{@code ROOM_UPDATE}             — {@link #mergeRoom(JSONObject)}</li>
 * </ul>
 *
 * <p>Subclass to add game-specific room fields:
 * <pre>
 * public class LotoRoomInfo extends RoomInfo {
 *     public long bet;
 *     public int  round;
 *
 *     &#64;Override
 *     public void mergeRoom(JSONObject j) {
 *         super.mergeRoom(j);
 *         if (j.has("bet"))   this.bet   = j.getLong("bet");
 *         if (j.has("round")) this.round = j.getInt("round");
 *     }
 *
 *     &#64;Override
 *     protected PlayerInfo createPlayer(JSONObject j) {
 *         return new LotoPlayerInfo(j);
 *     }
 * }
 * </pre>
 */
public class RoomInfo {

    public String  id;
    public String  name;
    public String  state;
    public int     maxPlayers;
    public boolean isPrivate;
    /** Raw JSON — access game-specific fields via raw.optXxx() until you subclass */
    public JSONObject raw;

    /** Players keyed by id — insertion-ordered for stable display */
    protected final Map<String, PlayerInfo> players = new LinkedHashMap<>();

    // ── Build from full snapshot ──────────────────────────────────────

    /**
     * Full replace — call on ROOM_SNAPSHOT / WELCOME.
     * Rebuilds the player map from the {@code players} array inside {@code roomJson}.
     */
    public void replaceFrom(JSONObject roomJson) {
        mergeRoom(roomJson);
        players.clear();
        JSONArray arr = roomJson.optJSONArray("players");
        if (arr != null) {
            for (int i = 0; i < arr.length(); i++) {
                JSONObject pj = arr.optJSONObject(i);
                if (pj != null) {
                    PlayerInfo pi = createPlayer(pj);
                    players.put(pi.id, pi);
                }
            }
        }
    }

    // ── Incremental player updates ────────────────────────────────────

    /** PLAYER_JOINED — add new player to map. */
    public void addPlayer(JSONObject playerJson) {
        PlayerInfo pi = createPlayer(playerJson);
        if (pi.id != null) players.put(pi.id, pi);
    }

    /**
     * PLAYER_LEFT permanent=false — player disconnected but is still a ghost.
     * Mark as offline, keep in list.
     */
    public void markOffline(String playerId) {
        PlayerInfo pi = players.get(playerId);
        if (pi != null) pi.connected = false;
    }

    /**
     * PLAYER_LEFT permanent=true — player left for good (timeout / kick / ban).
     * Remove from list entirely.
     */
    public void removePlayer(String playerId) {
        players.remove(playerId);
    }

    /** PLAYER_RECONNECTED — ghost came back, mark as online and refresh data. */
    public void markOnline(JSONObject playerJson) {
        String id = playerJson.optString("id", null);
        if (id == null) return;
        PlayerInfo pi = players.get(id);
        if (pi != null) {
            pi.connected = true;
            pi.merge(playerJson);
        } else {
            // Wasn't in our list — add them
            players.put(id, createPlayer(playerJson));
        }
    }

    /** PLAYER_UPDATE — merge changed fields for one player. */
    public void mergePlayer(JSONObject playerJson) {
        String id = playerJson.optString("id", null);
        if (id == null) return;
        PlayerInfo pi = players.get(id);
        if (pi != null) pi.merge(playerJson);
    }

    // ── Room-level updates ────────────────────────────────────────────

    /**
     * ROOM_UPDATE — merge changed room fields (bet, jackpot, state...).
     * Override to handle game-specific fields. Does NOT touch the player list.
     */
    public void mergeRoom(JSONObject j) {
        this.raw = j;
        if (j.has("id"))         this.id         = j.optString("id",    id);
        if (j.has("name"))       this.name       = j.optString("name",  name);
        if (j.has("state"))      this.state      = j.optString("state", state);
        if (j.has("maxPlayers")) this.maxPlayers = j.optInt("maxPlayers", maxPlayers);
        if (j.has("isPrivate"))  this.isPrivate  = j.optBoolean("isPrivate", false);
    }

    // ── Accessors ─────────────────────────────────────────────────────

    public Map<String, PlayerInfo> getPlayers() {
        return Collections.unmodifiableMap(players);
    }

    public PlayerInfo getPlayer(String id) {
        return players.get(id);
    }

    public int getPlayerCount() { return players.size(); }

    // ── Extension point ───────────────────────────────────────────────

    /**
     * Factory for creating PlayerInfo instances.
     * Override to return your own subclass:
     * <pre>
     * &#64;Override
     * protected PlayerInfo createPlayer(JSONObject j) {
     *     return new LotoPlayerInfo(j);
     * }
     * </pre>
     */
    protected PlayerInfo createPlayer(JSONObject j) {
        return new PlayerInfo(j);
    }

    @Override
    public String toString() {
        return "Room{id=" + id + ", name=" + name
                + ", state=" + state + ", players=" + players.size() + "}";
    }
}
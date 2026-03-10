package com.huydt.socket_base.client.core;

import com.huydt.socket_base.client.model.PlayerInfo;
import com.huydt.socket_base.client.model.RoomInfo;
import org.json.JSONObject;

/**
 * Holds the client's identity and current room state.
 *
 * <p>Room state is updated incrementally — call the appropriate method
 * from each event handler instead of replacing the whole room each time.
 *
 * <p>If your game has custom fields, supply your own {@link RoomInfo} subclass
 * via {@link #setRoomFactory(RoomFactory)}:
 * <pre>
 * session.setRoomFactory(LotoRoomInfo::new);
 * </pre>
 */
public final class ClientSession {

    // ── My identity ───────────────────────────────────────────────────
    private String  playerId;
    private String  token;
    private String  name;
    private boolean isAdmin;

    // ── Current room state ────────────────────────────────────────────
    private RoomInfo currentRoom;

    // ── Factory — swap out to use a RoomInfo subclass ─────────────────
    @FunctionalInterface
    public interface RoomFactory {
        RoomInfo create();
    }

    private RoomFactory roomFactory = RoomInfo::new;

    public void setRoomFactory(RoomFactory factory) {
        this.roomFactory = factory;
    }

    ClientSession() {}

    // ── Update from WELCOME / RECONNECTED ─────────────────────────────

    public void update(JSONObject payload) {
        JSONObject player = payload.optJSONObject("player");
        if (player != null) {
            this.playerId = player.optString("id",      playerId);
            this.token    = player.optString("token",   token);
            this.name     = player.optString("name",    name);
            this.isAdmin  = player.optBoolean("isAdmin", false);
        }

        Object roomRaw = payload.opt("room");
        if (roomRaw instanceof JSONObject) {
            // Full room snapshot — rebuild
            if (currentRoom == null) currentRoom = roomFactory.create();
            currentRoom.replaceFrom((JSONObject) roomRaw);
        } else {
            // null / JSONObject.NULL — player is in lobby
            currentRoom = null;
        }
    }

    // ── Incremental room updates ──────────────────────────────────────

    /** ROOM_SNAPSHOT received — full replace of current room. */
    public void onRoomSnapshot(JSONObject roomJson) {
        if (currentRoom == null) currentRoom = roomFactory.create();
        currentRoom.replaceFrom(roomJson);
    }

    /** PLAYER_JOINED — add to current room player list. */
    public void onPlayerJoined(JSONObject playerJson) {
        if (currentRoom != null) currentRoom.addPlayer(playerJson);
    }

    /**
     * PLAYER_LEFT received.
     * @param playerId  who left
     * @param permanent false = disconnected (ghost) → mark offline only
     *                  true  = left for good (leave/kick/ban/switch room) → remove
     */
    public void onPlayerLeft(String playerId, boolean permanent) {
        if (currentRoom == null) return;
        if (permanent) {
            if (playerId != null && playerId.equals(this.playerId)) {
                // It's us — we left the room
                currentRoom = null;
            } else {
                currentRoom.removePlayer(playerId);
            }
        } else {
            currentRoom.markOffline(playerId);
        }
    }

    /** PLAYER_RECONNECTED — ghost came back online. */
    public void onPlayerReconnected(JSONObject playerJson) {
        if (currentRoom != null) currentRoom.markOnline(playerJson);
    }

    /** PLAYER_UPDATE — merge changed fields for one player. */
    public void onPlayerUpdate(JSONObject playerJson) {
        if (currentRoom != null) currentRoom.mergePlayer(playerJson);
    }

    /** ROOM_UPDATE — merge changed room fields (no player list rebuild). */
    public void onRoomUpdate(JSONObject roomJson) {
        if (currentRoom != null) currentRoom.mergeRoom(roomJson);
    }

    /** Player left room voluntarily — clear local room state. */
    public void onLeftRoom() {
        currentRoom = null;
    }

    // ── Setters ───────────────────────────────────────────────────────

    public void setRoomId(String roomId) {
        // Only used by SocketBaseClient for ROOM_SNAPSHOT roomId tracking
        if (currentRoom != null && roomId != null) currentRoom.id = roomId;
    }

    public void setAdmin(boolean admin) { this.isAdmin = admin; }

    // ── Accessors ─────────────────────────────────────────────────────

    public String   getPlayerId()    { return playerId; }
    public String   getToken()       { return token; }
    public String   getName()        { return name; }
    public String   getRoomId()      { return currentRoom != null ? currentRoom.id : null; }
    public boolean  isAdmin()        { return isAdmin; }
    public boolean  isLoggedIn()     { return playerId != null; }
    public boolean  isInRoom()       { return currentRoom != null; }

    /** Current room state — null if in lobby. */
    public RoomInfo getCurrentRoom() { return currentRoom; }

    /** Convenience: get one player from current room. */
    public PlayerInfo getPlayer(String id) {
        return currentRoom != null ? currentRoom.getPlayer(id) : null;
    }

    @Override
    public String toString() {
        return "Session{id=" + playerId + ", name=" + name
                + ", room=" + getRoomId() + ", admin=" + isAdmin + "}";
    }
}

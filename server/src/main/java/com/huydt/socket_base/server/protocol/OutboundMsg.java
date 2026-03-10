package com.huydt.socket_base.server.protocol;

import com.huydt.socket_base.server.model.Player;
import com.huydt.socket_base.server.model.Room;
import org.json.JSONArray;
import org.json.JSONObject;

import java.util.Collection;

/**
 * Builder for all server → client messages.
 *
 * <h3>Usage</h3>
 * <pre>
 * handler.send(OutboundMsg.welcome(player, room).toJson());
 * handler.send(OutboundMsg.error("NOT_FOUND", "Room not found").toJson());
 * handler.send(OutboundMsg.custom("MY_TAG", myPayload).toJson());
 * </pre>
 */
public final class OutboundMsg {

    private final JSONObject json;

    private OutboundMsg(MsgType type, JSONObject payload) {
        json = new JSONObject();
        json.put("type",    type.name());
        json.put("payload", payload);
        json.put("ts",      System.currentTimeMillis());
    }

    public String toJson() { return json.toString(); }

    // ─── Factory methods ──────────────────────────────────────────────

    /**
     * Sent to the joining player after a successful JOIN.
     *
     * <p>Payload includes:
     * <ul>
     *   <li>{@code player} — full private snapshot via {@code player.toJson()}
     *       (includes token, metadata, all subclass fields)</li>
     *   <li>{@code room}   — full room snapshot via {@code room.toJson()} if in a room,
     *       {@code null} otherwise</li>
     * </ul>
     *
     * <p>Only send this to the player themselves — never broadcast it.
     */
    public static OutboundMsg welcome(Player player, Room room) {
        JSONObject p = new JSONObject();
        p.put("player", player.toJson());           // full private (has token + all subclass fields)
        p.put("room",   room != null ? room.toJson() : JSONObject.NULL);
        return new OutboundMsg(MsgType.WELCOME, p);
    }

    /**
     * Sent to a reconnecting player.
     * Same structure as {@link #welcome} — full private player + current room snapshot.
     */
    public static OutboundMsg reconnected(Player player, Room room) {
        JSONObject p = new JSONObject();
        p.put("player", player.toJson());           // full private
        p.put("room",   room != null ? room.toJson() : JSONObject.NULL);
        return new OutboundMsg(MsgType.RECONNECTED, p);
    }

    /**
     * Broadcast to room when a new player joins.
     * Uses {@link Player#toPublicJson()} — no token.
     */
    public static OutboundMsg playerJoined(Player player) {
        JSONObject p = new JSONObject();
        p.put("player", player.toPublicJson());     // public — broadcast safe
        return new OutboundMsg(MsgType.PLAYER_JOINED, p);
    }

    /** Broadcast to room when a player leaves or disconnects. */
    public static OutboundMsg playerLeft(String playerId, boolean permanent) {
        JSONObject p = new JSONObject();
        p.put("playerId",  playerId);
        p.put("permanent", permanent);
        return new OutboundMsg(MsgType.PLAYER_LEFT, p);
    }

    /**
     * Broadcast to room when a ghost player reconnects.
     * Uses {@link Player#toPublicJson()}.
     */
    public static OutboundMsg playerReconnected(Player player) {
        JSONObject p = new JSONObject();
        p.put("player", player.toPublicJson());
        return new OutboundMsg(MsgType.PLAYER_RECONNECTED, p);
    }

    /**
     * Sent to the player themselves + all members of their room when
     * a player's data changes (money, pages, score, ...).
     * Uses {@link Player#toPublicJson()} — no token exposed.
     */
    public static OutboundMsg playerUpdate(Player player) {
        JSONObject p = new JSONObject();
        p.put("player", player.toPublicJson());
        return new OutboundMsg(MsgType.PLAYER_UPDATE, p);
    }

    /**
     * Sent to all members of a room when the room's data changes
     * (bet, jackpot, round number, custom fields, ...).
     * Uses {@link com.huydt.socket_base.server.model.Room#toJson()}.
     */
    public static OutboundMsg roomUpdate(com.huydt.socket_base.server.model.Room room) {
        JSONObject p = new JSONObject();
        p.put("room", room.toJson());
        return new OutboundMsg(MsgType.ROOM_UPDATE, p);
    }

    /** Full room info snapshot. */
    public static OutboundMsg roomInfo(Room room) {
        return new OutboundMsg(MsgType.ROOM_INFO, room.toJson());
    }

    /**
     * Broadcast to players INSIDE a room when membership changes (join/leave/reconnect).
     * Clients use this to refresh the in-room player list.
     */
    public static OutboundMsg roomSnapshot(Room room) {
        JSONObject p = new JSONObject();
        p.put("room", room.toJson());
        return new OutboundMsg(MsgType.ROOM_SNAPSHOT, p);
    }

    /** Room state changed notification. */
    public static OutboundMsg roomStateChanged(Room room, String oldState, String newState) {
        JSONObject p = new JSONObject();
        p.put("roomId",   room.getId());
        p.put("roomName", room.getName());
        p.put("oldState", oldState);
        p.put("newState", newState);
        return new OutboundMsg(MsgType.ROOM_STATE_CHANGED, p);
    }

    /**
     * Sent to ALL players (server-wide) after a player joins or leaves a room.
     *
     * <p>Contains:
     * <ul>
     *   <li>{@code players} — public snapshot of every connected player
     *       (via {@link Player#toPublicJson()}, includes {@code roomId})</li>
     *   <li>{@code rooms}   — snapshot of every open room (via {@link Room#toJson()})</li>
     * </ul>
     *
     * <p>Clients use this to re-render the lobby / room-list view.
     */
    public static OutboundMsg appSnapshot(Collection<? extends Player> players,
                                           Collection<? extends Room>   rooms) {
        // Build roomId → roomName map for quick lookup
        java.util.Map<String, String> roomNames = new java.util.HashMap<>();
        rooms.forEach(r -> roomNames.put(r.getId(), r.getName()));

        JSONArray pArr = new JSONArray();
        players.forEach(p -> {
            JSONObject pj = p.toPublicJson();
            // Enrich with roomName so clients don't have to join on roomId themselves
            String roomName = p.getRoomId() != null ? roomNames.get(p.getRoomId()) : null;
            pj.put("roomName", roomName != null ? roomName : JSONObject.NULL);
            pArr.put(pj);
        });

        JSONArray rArr = new JSONArray();
        rooms.forEach(r -> rArr.put(r.toJson()));

        JSONObject payload = new JSONObject();
        payload.put("players", pArr);
        payload.put("rooms",   rArr);
        return new OutboundMsg(MsgType.APP_SNAPSHOT, payload);
    }

    /** List of rooms (response to LIST_ROOMS). */
    public static OutboundMsg roomList(Collection<? extends Room> rooms) {
        JSONArray arr = new JSONArray();
        rooms.forEach(r -> arr.put(r.toJson()));
        JSONObject p = new JSONObject();
        p.put("rooms", arr);
        return new OutboundMsg(MsgType.ROOM_LIST, p);
    }

    /** Sent to a player before closing their connection (kick). */
    public static OutboundMsg kicked(String reason) {
        JSONObject p = new JSONObject();
        p.put("reason", reason != null ? reason : "Kicked by admin");
        return new OutboundMsg(MsgType.KICKED, p);
    }

    /** Sent to a player who is banned. */
    public static OutboundMsg banned(String reason) {
        JSONObject p = new JSONObject();
        p.put("reason", reason != null ? reason : "Banned");
        return new OutboundMsg(MsgType.BANNED, p);
    }

    /** Admin auth success. */
    public static OutboundMsg adminAuthOk() {
        return new OutboundMsg(MsgType.ADMIN_AUTH_OK, new JSONObject());
    }

    /** Ban list response. */
    public static OutboundMsg banList(Collection<String> playerIds, Collection<String> ips) {
        JSONObject p = new JSONObject();
        JSONArray idArr = new JSONArray(); playerIds.forEach(idArr::put);
        JSONArray ipArr = new JSONArray(); ips.forEach(ipArr::put);
        p.put("playerIds", idArr);
        p.put("ips",       ipArr);
        return new OutboundMsg(MsgType.BAN_LIST, p);
    }

    /** Server stats. */
    public static OutboundMsg stats(int roomCount, int playerCount, long uptimeMs) {
        JSONObject p = new JSONObject();
        p.put("rooms",    roomCount);
        p.put("players",  playerCount);
        p.put("uptimeSec", uptimeMs / 1000);
        return new OutboundMsg(MsgType.STATS, p);
    }

    /**
     * Custom application message forwarded to the client.
     * @param tag   application-defined event tag
     * @param data  arbitrary payload (null = empty object)
     */
    public static OutboundMsg custom(String tag, JSONObject data) {
        JSONObject p = new JSONObject();
        p.put("tag",  tag);
        p.put("data", data != null ? data : new JSONObject());
        return new OutboundMsg(MsgType.CUSTOM_MSG, p);
    }

    /** Error response with a machine-readable code and human-readable detail. */
    public static OutboundMsg error(String code, String detail) {
        JSONObject p = new JSONObject();
        p.put("code",   code);
        p.put("detail", detail);
        return new OutboundMsg(MsgType.ERROR, p);
    }
}

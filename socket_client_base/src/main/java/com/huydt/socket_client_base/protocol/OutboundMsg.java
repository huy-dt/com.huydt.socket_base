package com.huydt.socket_client_base.protocol;

import org.json.JSONObject;

/**
 * Builds all messages the client sends to the server.
 *
 * <h3>Usage</h3>
 * <pre>
 * client.send(OutboundMsg.join("Alice"));
 * client.send(OutboundMsg.joinRoom("room1", null));
 * client.send(OutboundMsg.adminAuth("mysecret"));
 * client.send(OutboundMsg.kick("playerId123", "AFK"));
 * client.send(OutboundMsg.custom("BUY_ITEM", new JSONObject().put("itemId", 5)));
 * </pre>
 */
public final class OutboundMsg {

    private OutboundMsg() {}

    private static String build(MsgType type, JSONObject payload) {
        JSONObject msg = new JSONObject();
        msg.put("type",    type.name());
        msg.put("payload", payload != null ? payload : new JSONObject());
        msg.put("ts",      System.currentTimeMillis());
        return msg.toString();
    }

    // ── Connection ────────────────────────────────────────────────────

    /** Fresh join. */
    public static String join(String name) {
        return join(name, null, null);
    }

    /** Join with optional reconnect token and preferred roomId. */
    public static String join(String name, String token, String roomId) {
        JSONObject p = new JSONObject();
        if (name  != null) p.put("name",   name);
        if (token != null) p.put("token",  token);
        if (roomId!= null) p.put("roomId", roomId);
        return build(MsgType.JOIN, p);
    }

    /** Reconnect using a saved token. */
    public static String reconnect(String token) {
        return build(MsgType.RECONNECT, new JSONObject().put("token", token));
    }

    // ── Room ──────────────────────────────────────────────────────────

    /** Join a room. */
    public static String joinRoom(String roomId, String password) {
        JSONObject p = new JSONObject().put("roomId", roomId);
        if (password != null) p.put("password", password);
        return build(MsgType.JOIN_ROOM, p);
    }

    /** Leave current room. */
    public static String leaveRoom() {
        return build(MsgType.LEAVE_ROOM, null);
    }

    // ── Admin auth ────────────────────────────────────────────────────

    /** Authenticate as admin. */
    public static String adminAuth(String token) {
        return build(MsgType.ADMIN_AUTH, new JSONObject().put("token", token));
    }

    // ── Admin: player management ──────────────────────────────────────

    public static String kick(String playerId, String reason) {
        JSONObject p = new JSONObject().put("playerId", playerId);
        if (reason != null) p.put("reason", reason);
        return build(MsgType.KICK, p);
    }

    public static String ban(String playerId, String reason) {
        JSONObject p = new JSONObject().put("playerId", playerId);
        if (reason != null) p.put("reason", reason);
        return build(MsgType.BAN, p);
    }

    public static String unban(String name) {
        return build(MsgType.UNBAN, new JSONObject().put("name", name));
    }

    public static String banIp(String ip) {
        return build(MsgType.BAN_IP, new JSONObject().put("ip", ip));
    }

    public static String unbanIp(String ip) {
        return build(MsgType.UNBAN_IP, new JSONObject().put("ip", ip));
    }

    public static String getBanList() {
        return build(MsgType.GET_BAN_LIST, null);
    }

    // ── Admin: room management ────────────────────────────────────────

    public static String createRoom(String name) {
        return createRoom(name, 0, null);
    }

    public static String createRoom(String name, int maxPlayers, String password) {
        JSONObject p = new JSONObject().put("name", name);
        if (maxPlayers > 0) p.put("maxPlayers", maxPlayers);
        if (password != null) p.put("password", password);
        return build(MsgType.CREATE_ROOM, p);
    }

    public static String closeRoom(String roomId) {
        return build(MsgType.CLOSE_ROOM, new JSONObject().put("roomId", roomId));
    }

    public static String listRooms() {
        return build(MsgType.LIST_ROOMS, null);
    }

    public static String getRoom(String roomId) {
        return build(MsgType.GET_ROOM, new JSONObject().put("roomId", roomId));
    }

    public static String setRoomState(String roomId, String state) {
        return build(MsgType.SET_ROOM_STATE,
                new JSONObject().put("roomId", roomId).put("state", state));
    }

    // ── Admin: broadcast / send ───────────────────────────────────────

    /** Broadcast to a room (roomId=null → all players). */
    public static String adminBroadcast(String roomId, String tag, JSONObject data) {
        JSONObject p = new JSONObject().put("tag", tag);
        if (roomId != null) p.put("roomId", roomId);
        if (data   != null) p.put("data",   data);
        return build(MsgType.ADMIN_BROADCAST, p);
    }

    /** Send to a specific player. */
    public static String adminSend(String playerId, String tag, JSONObject data) {
        JSONObject p = new JSONObject().put("playerId", playerId).put("tag", tag);
        if (data != null) p.put("data", data);
        return build(MsgType.ADMIN_SEND, p);
    }

    /** Get server stats. */
    public static String getStats() {
        return build(MsgType.GET_STATS, null);
    }

    // ── Custom ────────────────────────────────────────────────────────

    /** Send a custom application event. */
    public static String custom(String tag, JSONObject data) {
        JSONObject p = new JSONObject().put("tag", tag);
        if (data != null) p.put("data", data);
        return build(MsgType.CUSTOM, p);
    }
}

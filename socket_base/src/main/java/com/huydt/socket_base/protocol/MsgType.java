package com.huydt.socket_base.protocol;

/**
 * All message types understood by the SDK.
 *
 * <h3>Wire format</h3>
 * <pre>
 * { "type": "JOIN", "payload": { "name": "Alice" } }
 * { "type": "WELCOME", "payload": { "playerId": "abc", "token": "xyz", "roomId": "room1" } }
 * </pre>
 */
public enum MsgType {

    // ══════════════════════════════════════════════════════════════
    // INBOUND  (Client → Server)
    // ══════════════════════════════════════════════════════════════

    /**
     * Initial join or reconnect attempt.
     * Payload: { name, token?, roomId? }
     * If token is present the server tries to reconnect first.
     */
    JOIN,

    /**
     * Explicit reconnect (legacy — new clients use JOIN + token).
     * Payload: { token }
     */
    RECONNECT,

    /**
     * Join a specific room.
     * Payload: { roomId, password? }
     */
    JOIN_ROOM,

    /**
     * Leave current room but stay connected.
     * Payload: {}
     */
    LEAVE_ROOM,

    /**
     * Admin authentication.
     * Payload: { token }
     */
    ADMIN_AUTH,

    // ── Admin-only inbound ─────────────────────────────────────────

    /** Kick a player. Payload: { playerId, reason? } */
    KICK,

    /** Ban a player by id. Payload: { playerId, reason? } */
    BAN,

    /** Unban by player name. Payload: { name } */
    UNBAN,

    /** Ban an IP address. Payload: { ip } */
    BAN_IP,

    /** Unban an IP address. Payload: { ip } */
    UNBAN_IP,

    /** Get ban lists. Payload: {} */
    GET_BAN_LIST,

    /** Create a new room. Payload: { name, maxPlayers?, password? } */
    CREATE_ROOM,

    /** Close/delete a room. Payload: { roomId } */
    CLOSE_ROOM,

    /** List all rooms. Payload: {} */
    LIST_ROOMS,

    /** Get room details. Payload: { roomId } */
    GET_ROOM,

    /** Change a room's state. Payload: { roomId, state } */
    SET_ROOM_STATE,

    /** Broadcast a message to all players in a room. Payload: { roomId, data } */
    ADMIN_BROADCAST,

    /** Send a message to a specific player. Payload: { playerId, data } */
    ADMIN_SEND,

    /** Get server stats. Payload: {} */
    GET_STATS,

    // ── Extension ─────────────────────────────────────────────────
    /** Custom application message. Payload: { tag, data? } */
    CUSTOM,


    // ══════════════════════════════════════════════════════════════
    // OUTBOUND  (Server → Client)
    // ══════════════════════════════════════════════════════════════

    /**
     * Sent to the player after a successful JOIN.
     * Payload: { playerId, token, roomId, roomSnapshot? }
     */
    WELCOME,

    /** Player was reconnected to their slot. Payload: { playerId, roomId } */
    RECONNECTED,

    /** Room list response. Payload: { rooms: [] } */
    ROOM_LIST,

    /** Snapshot of a single room. Payload: room json */
    ROOM_INFO,

    /**
     * Broadcast to ALL players after any join/leave room event.
     * Payload: { players: Player[], rooms: Room[] }
     * Clients use this to refresh the lobby / room-list view.
     */
    APP_SNAPSHOT,

    /**
     * Broadcast to players INSIDE a room when room membership changes.
     * Payload: { room: Room }
     */
    ROOM_SNAPSHOT,

    /** A new player joined the room. Payload: { player } */
    PLAYER_JOINED,

    /** A player left or disconnected. Payload: { playerId, permanent } */
    PLAYER_LEFT,

    /** A player reconnected. Payload: { player } */
    PLAYER_RECONNECTED,

    /**
     * A player's data changed (money, pages, status, ...).
     * Sent to the player themselves + all members of their current room.
     * Payload: { player } — public snapshot via toPublicJson()
     */
    PLAYER_UPDATE,

    /**
     * A room's data changed (bet, jackpot, round, ...).
     * Sent to all members of that room.
     * Payload: { room } — full room snapshot via toJson()
     */
    ROOM_UPDATE,

    /** Room state changed. Payload: { roomId, oldState, newState } */
    ROOM_STATE_CHANGED,

    /** Player was kicked. Payload: { reason } (sent to kicked player only) */
    KICKED,

    /** Player was banned. Payload: { reason } */
    BANNED,

    /** Player was unbanned. Payload: { name } */
    UNBANNED,

    /** Ban list. Payload: { playerIds: [], ips: [] } */
    BAN_LIST,

    /** Admin auth success. Payload: {} */
    ADMIN_AUTH_OK,

    /** Server stats. Payload: { rooms, players, uptime } */
    STATS,

    /** Custom application message forwarded to client. Payload: { tag, data? } */
    CUSTOM_MSG,

    /** Error response. Payload: { code, detail } */
    ERROR
}

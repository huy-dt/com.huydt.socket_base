package com.huydt.socket_client_base.protocol;

/**
 * All message types shared with the server.
 * Keep in sync with {@code com.huydt.socket_base.protocol.MsgType}.
 */
public enum MsgType {

    // ══════════════════════════════════════════════════════════════
    // OUTBOUND  (Client → Server)
    // ══════════════════════════════════════════════════════════════

    /** Initial join or reconnect attempt. Payload: { name, token?, roomId? } */
    JOIN,

    /** Explicit reconnect (legacy). Payload: { token } */
    RECONNECT,

    /** Join a specific room. Payload: { roomId, password? } */
    JOIN_ROOM,

    /** Leave current room but stay connected. */
    LEAVE_ROOM,

    /** Admin authentication. Payload: { token } */
    ADMIN_AUTH,

    // Admin-only outbound
    KICK,
    BAN,
    UNBAN,
    BAN_IP,
    UNBAN_IP,
    GET_BAN_LIST,
    CREATE_ROOM,
    CLOSE_ROOM,
    LIST_ROOMS,
    GET_ROOM,
    SET_ROOM_STATE,
    ADMIN_BROADCAST,
    ADMIN_SEND,
    GET_STATS,

    /** Custom application message. Payload: { tag, data? } */
    CUSTOM,

    // ══════════════════════════════════════════════════════════════
    // INBOUND  (Server → Client)
    // ══════════════════════════════════════════════════════════════

    WELCOME,
    RECONNECTED,
    ROOM_LIST,
    ROOM_INFO,
    APP_SNAPSHOT,
    ROOM_SNAPSHOT,
    PLAYER_JOINED,
    PLAYER_LEFT,
    PLAYER_RECONNECTED,
    PLAYER_UPDATE,
    ROOM_UPDATE,
    ROOM_STATE_CHANGED,
    KICKED,
    BANNED,
    UNBANNED,
    BAN_LIST,
    ADMIN_AUTH_OK,
    STATS,
    CUSTOM_MSG,
    ERROR
}
package com.huydt.socket_base.client.event;

/**
 * All events the client can emit to registered listeners.
 */
public enum ClientEventType {

    // ── Connection lifecycle ──────────────────────────────────────────
    CONNECTED,          // socket connected, before JOIN sent
    DISCONNECTED,       // socket dropped

    // ── Session ───────────────────────────────────────────────────────
    WELCOME,            // server accepted our JOIN — we have playerId + token
    RECONNECTED,        // server accepted our reconnect token

    // ── Room ─────────────────────────────────────────────────────────
    ROOM_INFO,          // full room snapshot received (response to GET_ROOM)
    ROOM_LIST,          // room list received
    ROOM_STATE_CHANGED, // room state updated
    ROOM_SNAPSHOT,      // live room state pushed by server (while inside a room)
    PLAYER_JOINED,      // another player joined our room
    PLAYER_LEFT,        // another player left our room
    PLAYER_RECONNECTED, // a ghost player reconnected

    /** A player's data changed — payload: { player } */
    PLAYER_UPDATE,

    /** A room's data changed — payload: { room } */
    ROOM_UPDATE,

    // ── Lobby ─────────────────────────────────────────────────────────
    APP_SNAPSHOT,       // full players[] + rooms[] — only received while in lobby

    // ── Admin ─────────────────────────────────────────────────────────
    ADMIN_AUTH_OK,
    STATS,
    BAN_LIST,

    // ── Moderation ───────────────────────────────────────────────────
    KICKED,
    BANNED,

    // ── Custom / misc ─────────────────────────────────────────────────
    CUSTOM_MSG,         // server forwarded a CUSTOM_MSG
    ERROR,              // server sent ERROR or local exception
}

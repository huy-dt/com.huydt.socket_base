package com.huydt.socket_base.server.event;

/**
 * All built-in server events.
 * Extend with your own enum or use {@link #CUSTOM} + a tag string.
 */
public enum EventType {

    // ── Server lifecycle ──────────────────────────────────────────────
    SERVER_STARTED,
    SERVER_STOPPED,

    // ── Player lifecycle ──────────────────────────────────────────────
    /** A new player successfully joined a room. */
    PLAYER_JOINED,

    /** A player reconnected after being a ghost. */
    PLAYER_RECONNECTED,

    /** A player's socket dropped (may reconnect within timeout). */
    PLAYER_DISCONNECTED,

    /** A player was permanently removed (timeout expired or kicked/banned). */
    PLAYER_LEFT,

    /** A player was kicked by an admin. */
    PLAYER_KICKED,

    /** A player was banned. */
    PLAYER_BANNED,

    /** A player was unbanned. */
    PLAYER_UNBANNED,

    // ── Room lifecycle ────────────────────────────────────────────────
    /** A new room was created. */
    ROOM_CREATED,

    /** A room was closed / removed. */
    ROOM_CLOSED,

    /** Room state changed (WAITING → PLAYING, etc.). */
    ROOM_STATE_CHANGED,

    /** A player joined a room. */
    ROOM_PLAYER_JOINED,

    /** A player left a room. */
    ROOM_PLAYER_LEFT,

    // ── Messaging ─────────────────────────────────────────────────────
    /** A raw inbound message arrived (before routing). */
    MESSAGE_RECEIVED,

    /** A message was sent to a single player. */
    MESSAGE_SENT,

    /** A broadcast was emitted to a room. */
    BROADCAST,

    // ── Admin ─────────────────────────────────────────────────────────
    /** An admin session was authenticated. */
    ADMIN_AUTH,

    /** An admin command was executed. */
    ADMIN_COMMAND,

    // ── Errors ────────────────────────────────────────────────────────
    /** A server-side error occurred. */
    ERROR,

    // ── Extension ─────────────────────────────────────────────────────
    /** For application-defined events. Carry extra info in the tag field. */
    CUSTOM
}

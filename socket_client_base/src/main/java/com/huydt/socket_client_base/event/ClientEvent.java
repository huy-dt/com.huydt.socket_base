package com.huydt.socket_client_base.event;

import org.json.JSONObject;

/**
 * Immutable event object delivered to {@link ClientEventListener} callbacks.
 *
 * <h3>Usage</h3>
 * <pre>
 * client.on(ClientEventType.WELCOME, e -> {
 *     System.out.println("My id: " + e.getPlayerId());
 *     System.out.println("Token: " + e.getToken());
 * });
 *
 * client.on(ClientEventType.ERROR, e -> System.err.println(e.getMessage()));
 * </pre>
 */
public final class ClientEvent {

    private final ClientEventType type;
    private final JSONObject       payload;   // raw server payload (may be null)
    private final String           message;   // human-readable detail or error
    private final Throwable        cause;     // set for local exceptions

    private ClientEvent(Builder b) {
        this.type    = b.type;
        this.payload = b.payload;
        this.message = b.message;
        this.cause   = b.cause;
    }

    // ── Accessors ─────────────────────────────────────────────────────

    public ClientEventType getType()    { return type; }
    public JSONObject      getPayload() { return payload; }
    public String          getMessage() { return message; }
    public Throwable       getCause()   { return cause; }

    /** Convenience — read a string from the raw payload. */
    public String getString(String key) {
        return payload != null ? payload.optString(key, null) : null;
    }

    /** Convenience — read a nested object from the raw payload. */
    public JSONObject getObject(String key) {
        return payload != null ? payload.optJSONObject(key) : null;
    }

    /** The connected player's id (from WELCOME / RECONNECTED payload). */
    public String getPlayerId() { return getString("playerId"); }

    /** The reconnect token (from WELCOME payload). */
    public String getToken() {
        // token lives inside the nested "player" object in WELCOME
        JSONObject player = getObject("player");
        return player != null ? player.optString("token", null) : null;
    }

    /** The room id the player is currently in. */
    public String getRoomId() {
        JSONObject player = getObject("player");
        if (player != null) return player.optString("roomId", null);
        return getString("roomId");
    }

    // ── Builder ───────────────────────────────────────────────────────

    public static final class Builder {
        private final ClientEventType type;
        private JSONObject             payload;
        private String                 message;
        private Throwable              cause;

        public Builder(ClientEventType type) { this.type = type; }

        public Builder payload(JSONObject p) { this.payload = p; return this; }
        public Builder message(String m)     { this.message = m; return this; }
        public Builder cause(Throwable t)    { this.cause = t; return this; }

        public ClientEvent build() { return new ClientEvent(this); }
    }

    /** Quick factory for error events. */
    public static ClientEvent error(String msg, Throwable cause) {
        return new Builder(ClientEventType.ERROR).message(msg).cause(cause).build();
    }

    /** Quick factory for events with only a payload. */
    public static ClientEvent of(ClientEventType type, JSONObject payload) {
        return new Builder(type).payload(payload).build();
    }

    @Override
    public String toString() {
        return "ClientEvent{type=" + type + ", message=" + message + "}";
    }
}

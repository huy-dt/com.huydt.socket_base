package com.huydt.socket_base.protocol;

import org.json.JSONObject;

/**
 * Parsed message received from a client.
 * Wire format: { "type": "JOIN", "payload": { ... } }
 */
public final class InboundMsg {

    private final MsgType   type;
    private final JSONObject payload;
    private final String     raw;

    private InboundMsg(MsgType type, JSONObject payload, String raw) {
        this.type    = type;
        this.payload = payload;
        this.raw     = raw;
    }

    /** Returns null if the message is malformed or has an unknown type. */
    public static InboundMsg parse(String rawJson) {
        try {
            JSONObject json    = new JSONObject(rawJson.trim());
            String     typeStr = json.getString("type");
            MsgType    type    = MsgType.valueOf(typeStr);
            JSONObject payload = json.optJSONObject("payload");
            return new InboundMsg(type, payload != null ? payload : new JSONObject(), rawJson);
        } catch (Exception e) {
            return null;
        }
    }

    // ── Accessors ─────────────────────────────────────────────────────

    public MsgType   getType()    { return type; }
    public JSONObject getPayload(){ return payload; }
    public String    getRaw()     { return raw; }

    public String  getString(String key)                   { return payload.optString(key, null); }
    public String  getString(String key, String def)       { return payload.optString(key, def); }
    public int     getInt(String key, int def)             { return payload.optInt(key, def); }
    public long    getLong(String key, long def)           { return payload.optLong(key, def); }
    public boolean getBoolean(String key, boolean def)     { return payload.optBoolean(key, def); }
    public JSONObject getObject(String key)                { return payload.optJSONObject(key); }

    @Override
    public String toString() { return "InboundMsg{type=" + type + ", payload=" + payload + "}"; }
}

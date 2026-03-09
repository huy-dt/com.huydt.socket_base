package com.huydt.socket_client_base.protocol;

import org.json.JSONObject;

/**
 * Parsed message received from the server.
 * Wire format: { "type": "WELCOME", "payload": { ... }, "ts": 1234567890 }
 */
public final class InboundMsg {

    private final MsgType    type;
    private final JSONObject payload;
    private final long       ts;
    private final String     raw;

    private InboundMsg(MsgType type, JSONObject payload, long ts, String raw) {
        this.type    = type;
        this.payload = payload;
        this.ts      = ts;
        this.raw     = raw;
    }

    /** Returns null if malformed or unknown type. */
    public static InboundMsg parse(String rawJson) {
        try {
            JSONObject json    = new JSONObject(rawJson.trim());
            String     typeStr = json.getString("type");
            MsgType    type    = MsgType.valueOf(typeStr);
            JSONObject payload = json.optJSONObject("payload");
            long       ts      = json.optLong("ts", 0);
            return new InboundMsg(type, payload != null ? payload : new JSONObject(), ts, rawJson);
        } catch (Exception e) {
            return null;
        }
    }

    // ── Accessors ─────────────────────────────────────────────────────

    public MsgType    getType()    { return type; }
    public JSONObject getPayload() { return payload; }
    public long       getTs()      { return ts; }
    public String     getRaw()     { return raw; }

    public String     getString(String key)              { return payload.optString(key, null); }
    public String     getString(String key, String def)  { return payload.optString(key, def); }
    public int        getInt(String key, int def)        { return payload.optInt(key, def); }
    public long       getLong(String key, long def)      { return payload.optLong(key, def); }
    public boolean    getBoolean(String key, boolean def){ return payload.optBoolean(key, def); }
    public JSONObject getObject(String key)              { return payload.optJSONObject(key); }
    public org.json.JSONArray getArray(String key)       { return payload.optJSONArray(key); }

    @Override
    public String toString() {
        return "InboundMsg{type=" + type + ", payload=" + payload + "}";
    }
}

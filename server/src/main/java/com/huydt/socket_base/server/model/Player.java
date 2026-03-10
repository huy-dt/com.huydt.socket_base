package com.huydt.socket_base.server.model;

import org.json.JSONObject;
import java.util.UUID;

/**
 * Represents a connected (or temporarily disconnected) player/client.
 *
 * <p>Tracks:
 * <ul>
 *   <li>{@code id}       — short stable identifier used by the server</li>
 *   <li>{@code token}    — secret reconnect token given to the client on JOIN</li>
 *   <li>{@code name}     — display name provided by the client</li>
 *   <li>{@code connId}   — current live connection id (changes after reconnect)</li>
 *   <li>{@code roomId}   — room this player is currently in (null = lobby)</li>
 *   <li>{@code connected} — whether the player has an active socket</li>
 *   <li>{@code metadata} — free-form key/value for game-specific extensions</li>
 * </ul>
 *
 * <p>Extend this class to add game-specific fields:
 * <pre>
 * public class LotoPlayer extends Player {
 *     public List&lt;Page&gt; pages = new ArrayList&lt;&gt;();
 *     public long balance = 0;
 * }
 * </pre>
 */
public class Player extends BaseModel {

    private String  token;
    private String  name;
    private String  connId;       // current transport connection id
    private String  roomId;       // null = not in any room
    private boolean connected;
    private boolean isAdmin;
    private long    joinedAt;
    private long    disconnectedAt; // 0 = currently connected
    private JSONObject metadata;    // free-form extras

    public Player(String name) {
        super();
        this.token         = UUID.randomUUID().toString();
        this.name          = sanitize(name);
        this.connected     = true;
        this.isAdmin       = false;
        this.joinedAt      = System.currentTimeMillis();
        this.disconnectedAt = 0;
        this.metadata      = new JSONObject();
    }

    /** Restore a player from persistence. */
    public static Player restore(String id, String token, String name, String roomId) {
        Player p = new Player(name);
        p.setId(id);
        p.token     = token;
        p.roomId    = roomId;
        p.connected = false;
        return p;
    }

    // ── Accessors ─────────────────────────────────────────────────────

    public String  getToken()           { return token; }
    public String  getName()            { return name; }
    public String  getConnId()          { return connId; }
    public String  getRoomId()          { return roomId; }
    public boolean isConnected()        { return connected; }
    public boolean isAdmin()            { return isAdmin; }
    public long    getJoinedAt()        { return joinedAt; }
    public long    getDisconnectedAt()  { return disconnectedAt; }
    public JSONObject getMetadata()     { return metadata; }

    // ── Mutators ──────────────────────────────────────────────────────

    public void setConnId(String connId)     { this.connId = connId; }
    public void setRoomId(String roomId)     { this.roomId = roomId; }
    public void setName(String name)         { this.name = sanitize(name); }
    public void setAdmin(boolean admin)      { this.isAdmin = admin; }

    public void markConnected(String newConnId) {
        this.connId          = newConnId;
        this.connected       = true;
        this.disconnectedAt  = 0;
    }

    public void markDisconnected() {
        this.connected       = false;
        this.disconnectedAt  = System.currentTimeMillis();
        this.connId          = null;
    }

    /** Store a custom metadata value. */
    public void setMeta(String key, Object value) {
        metadata.put(key, value);
    }

    public Object getMeta(String key) {
        return metadata.opt(key);
    }

    // ── Serialization ─────────────────────────────────────────────────

    /**
     * Returns the <b>complete</b> player object including all subclass fields.
     *
     * <p>Contains the private {@code token} — only send this to the player themselves
     * (in WELCOME / RECONNECTED).  Use {@link #toPublicJson()} for broadcasts.
     *
     * <p>Subclasses <b>must</b> override this and call {@code super.toJson()}:
     * <pre>
     * &#64;Override
     * public JSONObject toJson() {
     *     JSONObject j = super.toJson();
     *     j.put("balance",   balance);
     *     j.put("pageCount", pages.size());
     *     return j;
     * }
     * </pre>
     */
    @Override
    public JSONObject toJson() {
        JSONObject j = super.toJson();
        j.put("name",           name);
        j.put("token",          token);          // private — strip before broadcasting
        j.put("roomId",         roomId != null ? roomId : JSONObject.NULL);
        j.put("connected",      connected);
        j.put("isAdmin",        isAdmin);
        j.put("joinedAt",       joinedAt);
        j.put("disconnectedAt", disconnectedAt);
        j.put("metadata",       metadata);
        return j;
    }

    /**
     * Public-safe snapshot — same as {@link #toJson()} but with {@code token}
     * and internal fields stripped.
     *
     * <p>This is what gets broadcast to other players in the room (PLAYER_JOINED,
     * ROOM_INFO players array, etc.).
     *
     * <p>Subclasses should override this alongside {@code toJson()} to expose any
     * additional game fields that are public:
     * <pre>
     * &#64;Override
     * public JSONObject toPublicJson() {
     *     JSONObject j = super.toPublicJson();  // already has id, name, roomId, …
     *     j.put("score", score);                // safe to share
     *     // do NOT add balance or private fields here
     *     return j;
     * }
     * </pre>
     */
    public JSONObject toPublicJson() {
        JSONObject full = toJson();
        full.remove("token");           // strip private fields
        full.remove("disconnectedAt");
        full.remove("metadata");
        return full;
    }

    @Override
    public void fromJson(JSONObject j) {
        super.fromJson(j);
        this.name      = j.optString("name", name);
        this.roomId    = j.optString("roomId", null);
        this.connected = j.optBoolean("connected", false);
        this.isAdmin   = j.optBoolean("isAdmin", false);
        this.joinedAt  = j.optLong("joinedAt", joinedAt);
    }

    @Override
    public String toString() {
        return "Player{id=" + getId() + ", name=" + name + ", room=" + roomId +
               ", connected=" + connected + "}";
    }

    // ── Util ──────────────────────────────────────────────────────────

    private static String sanitize(String name) {
        if (name == null) return "Unknown";
        name = name.trim();
        return name.isEmpty() ? "Unknown" : name.substring(0, Math.min(name.length(), 32));
    }
}

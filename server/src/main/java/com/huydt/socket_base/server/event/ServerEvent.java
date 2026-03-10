package com.huydt.socket_base.server.event;

import com.huydt.socket_base.server.model.Player;
import com.huydt.socket_base.server.model.Room;

import java.util.Collections;
import java.util.HashMap;
import java.util.Map;

/**
 * Immutable event fired by the server and delivered to all registered
 * {@link ServerEventListener}s.
 *
 * <h3>Usage</h3>
 * <pre>
 * bus.on(EventType.PLAYER_JOINED, e -> {
 *     Player p = e.getPlayer();
 *     Room   r = e.getRoom();
 *     log("Welcome " + p.getName() + " to " + r.getName());
 * });
 * </pre>
 */
public final class ServerEvent {

    private final EventType         type;
    private final Player            player;    // may be null
    private final Room              room;      // may be null
    private final String            message;   // human-readable detail
    private final String            tag;       // for CUSTOM events
    private final Map<String,Object> data;     // extra typed payload
    private final long              timestamp;

    private ServerEvent(Builder b) {
        this.type      = b.type;
        this.player    = b.player;
        this.room      = b.room;
        this.message   = b.message;
        this.tag       = b.tag;
        this.data      = Collections.unmodifiableMap(new HashMap<>(b.data));
        this.timestamp = System.currentTimeMillis();
    }

    // ── Accessors ─────────────────────────────────────────────────────

    public EventType  getType()    { return type; }
    public Player     getPlayer()  { return player; }
    public Room       getRoom()    { return room; }
    public String     getMessage() { return message; }
    public String     getTag()     { return tag; }
    public long       getTimestamp(){ return timestamp; }

    @SuppressWarnings("unchecked")
    public <T> T get(String key) { return (T) data.get(key); }

    // ── Convenience factory methods ───────────────────────────────────

    public static ServerEvent of(EventType type) {
        return new Builder(type).build();
    }

    public static ServerEvent of(EventType type, Player player) {
        return new Builder(type).player(player).build();
    }

    public static ServerEvent of(EventType type, Player player, Room room) {
        return new Builder(type).player(player).room(room).build();
    }

    public static ServerEvent of(EventType type, Room room) {
        return new Builder(type).room(room).build();
    }

    public static ServerEvent of(EventType type, String message) {
        return new Builder(type).message(message).build();
    }

    public static ServerEvent error(String message) {
        return new Builder(EventType.ERROR).message(message).build();
    }

    public static ServerEvent custom(String tag) {
        return new Builder(EventType.CUSTOM).tag(tag).build();
    }

    // ── Builder ───────────────────────────────────────────────────────

    public static class Builder {
        private final EventType    type;
        private Player             player;
        private Room               room;
        private String             message;
        private String             tag;
        private Map<String,Object> data = new HashMap<>();

        public Builder(EventType type) { this.type = type; }

        public Builder player(Player p)         { this.player = p;  return this; }
        public Builder room(Room r)             { this.room = r;    return this; }
        public Builder message(String msg)      { this.message = msg; return this; }
        public Builder tag(String tag)          { this.tag = tag;   return this; }
        public Builder data(String key, Object v){ this.data.put(key, v); return this; }

        public ServerEvent build() { return new ServerEvent(this); }
    }

    @Override
    public String toString() {
        return "ServerEvent{type=" + type + ", player=" + player +
               ", room=" + room + ", msg=" + message + "}";
    }
}

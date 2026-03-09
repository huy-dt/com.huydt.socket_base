package com.huydt.socket_base.model;

import org.json.JSONArray;
import org.json.JSONObject;
import java.util.*;
import java.util.concurrent.ConcurrentHashMap;

/**
 * Represents a game room that players can join/leave.
 *
 * <p>Tracks:
 * <ul>
 *   <li>{@code id}      — unique room identifier</li>
 *   <li>{@code name}    — human-readable room name</li>
 *   <li>{@code state}   — current room state (WAITING, PLAYING, …)</li>
 *   <li>{@code players} — players currently in this room (by playerId)</li>
 *   <li>{@code maxPlayers} — 0 = unlimited</li>
 *   <li>{@code metadata}  — free-form key/value for game-specific extensions</li>
 * </ul>
 *
 * <p>Extend to add game logic:
 * <pre>
 * public class LotoRoom extends Room {
 *     public List&lt;Integer&gt; drawnNumbers = new ArrayList&lt;&gt;();
 *     public long jackpot = 0;
 * }
 * </pre>
 */
public class Room extends BaseModel {

    /** Built-in room states. Add custom states by extending {@link RoomState}. */
    public enum RoomState {
        WAITING, STARTING, PLAYING, PAUSED, ENDED, CLOSED
    }

    private String    name;
    private RoomState state;
    private int       maxPlayers;   // 0 = unlimited
    private boolean   isPrivate;
    private String    password;     // null = open room
    private JSONObject metadata;

    // player ids ordered by join time
    private final List<String>              playerOrder = new ArrayList<>();
    private final Map<String, Player>       playersById = new ConcurrentHashMap<>();

    public Room(String name) {
        this(name, 0);
    }

    public Room(String name, int maxPlayers) {
        super();
        this.name       = name;
        this.state      = RoomState.WAITING;
        this.maxPlayers = maxPlayers;
        this.isPrivate  = false;
        this.metadata   = new JSONObject();
    }

    public Room(String id, String name, int maxPlayers) {
        super(id);
        this.name       = name;
        this.state      = RoomState.WAITING;
        this.maxPlayers = maxPlayers;
        this.metadata   = new JSONObject();
    }

    // ── Player management ─────────────────────────────────────────────

    /**
     * Adds a player to this room.
     * @return false if full or player already in room
     */
    public synchronized boolean addPlayer(Player player) {
        if (isFull()) return false;
        if (playersById.containsKey(player.getId())) return false;
        playersById.put(player.getId(), player);
        playerOrder.add(player.getId());
        player.setRoomId(getId());
        return true;
    }

    /**
     * Removes a player from this room.
     * @return false if player was not in room
     */
    public synchronized boolean removePlayer(String playerId) {
        Player p = playersById.remove(playerId);
        if (p == null) return false;
        playerOrder.remove(playerId);
        p.setRoomId(null);
        return true;
    }

    public boolean hasPlayer(String playerId)          { return playersById.containsKey(playerId); }
    public Player  getPlayer(String playerId)          { return playersById.get(playerId); }
    public int     getPlayerCount()                    { return playersById.size(); }
    public boolean isEmpty()                           { return playersById.isEmpty(); }
    public boolean isFull()                            { return maxPlayers > 0 && playersById.size() >= maxPlayers; }

    /** Returns an immutable snapshot of players ordered by join time. */
    public synchronized List<Player> getPlayers() {
        List<Player> list = new ArrayList<>();
        for (String id : playerOrder) {
            Player p = playersById.get(id);
            if (p != null) list.add(p);
        }
        return Collections.unmodifiableList(list);
    }

    /** Returns count of currently connected (non-ghost) players. */
    public long getConnectedCount() {
        return playersById.values().stream().filter(Player::isConnected).count();
    }

    // ── Accessors ─────────────────────────────────────────────────────

    public String    getName()       { return name; }
    public RoomState getState()      { return state; }
    public int       getMaxPlayers() { return maxPlayers; }
    public boolean   isPrivate()     { return isPrivate; }
    public JSONObject getMetadata()  { return metadata; }

    // ── Mutators ──────────────────────────────────────────────────────

    public void setState(RoomState state)        { this.state = state; }
    public void setName(String name)             { this.name = name; }
    public void setMaxPlayers(int max)           { this.maxPlayers = max; }
    public void setPrivate(boolean priv)         { this.isPrivate = priv; }
    public void setPassword(String password)     { this.password = password; }
    public boolean checkPassword(String pass)    { return password == null || password.equals(pass); }

    public void setMeta(String key, Object value) { metadata.put(key, value); }
    public Object getMeta(String key)             { return metadata.opt(key); }

    // ── Serialization ─────────────────────────────────────────────────

    /**
     * Returns the complete room snapshot.
     *
     * <p>The {@code players} array is built by calling {@link Player#toPublicJson()}
     * on each member, so any extra fields added by a subclass of Player are
     * automatically included — no changes needed here.
     *
     * <p>Subclasses extend this the usual way:
     * <pre>
     * &#64;Override
     * public JSONObject toJson() {
     *     JSONObject j = super.toJson();   // ← id, name, state, players, …
     *     j.put("jackpot",      jackpot);
     *     j.put("drawnNumbers", new JSONArray(drawnNumbers));
     *     return j;
     * }
     * </pre>
     */
    @Override
    public JSONObject toJson() {
        JSONObject j = super.toJson();
        j.put("name",         name);
        j.put("state",        state.name());
        j.put("maxPlayers",   maxPlayers);
        j.put("playerCount",  getPlayerCount());
        j.put("connectedCount", getConnectedCount());
        j.put("isPrivate",    isPrivate);
        j.put("isFull",       isFull());
        j.put("metadata",     metadata);

        JSONArray arr = new JSONArray();
        getPlayers().forEach(p -> arr.put(p.toPublicJson()));
        j.put("players", arr);
        return j;
    }

    @Override
    public String toString() {
        return "Room{id=" + getId() + ", name=" + name + ", state=" + state +
               ", players=" + getPlayerCount() + "/" + (maxPlayers == 0 ? "∞" : maxPlayers) + "}";
    }
}

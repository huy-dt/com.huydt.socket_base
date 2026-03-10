package com.huydt.socket_base.server.core;

import com.huydt.socket_base.server.event.EventBus;
import com.huydt.socket_base.server.event.EventType;
import com.huydt.socket_base.server.event.ServerEvent;
import com.huydt.socket_base.server.model.Player;
import com.huydt.socket_base.server.model.Room;
import com.huydt.socket_base.server.protocol.OutboundMsg;

import java.util.*;
import java.util.concurrent.ConcurrentHashMap;

/**
 * Creates, manages, and closes {@link Room} instances.
 *
 * <p>Thread-safe. All mutations are synchronized.
 */
public class RoomManager {

    private final ServerConfig config;
    private final EventBus     bus;
    private final PlayerManager playerManager;

    /** roomId → Room */
    private final Map<String, Room> rooms = new ConcurrentHashMap<>();

    public RoomManager(ServerConfig config, PlayerManager playerManager, EventBus bus) {
        this.config        = config;
        this.playerManager = playerManager;
        this.bus           = bus;
    }

    // ── Room lifecycle ────────────────────────────────────────────────

    /** Creates a new room with an auto-generated id. */
    public synchronized Room createRoom(String name) {
        return createRoom(UUID.randomUUID().toString().replace("-", "").substring(0, 8), name, 0);
    }

    /** Creates a room with explicit id, name, and max players. */
    public synchronized Room createRoom(String roomId, String name, int maxPlayers) {
        if (config.maxRooms > 0 && rooms.size() >= config.maxRooms) {
            throw new IllegalStateException("Max rooms reached: " + config.maxRooms);
        }
        if (rooms.containsKey(roomId)) {
            throw new IllegalArgumentException("Room already exists: " + roomId);
        }
        int cap = (config.maxPlayersPerRoom > 0 && maxPlayers == 0)
                    ? config.maxPlayersPerRoom : maxPlayers;

        Room room = newRoom(roomId, name, cap);
        rooms.put(roomId, room);
        System.out.printf("[RoomManager] Created room '%s' (%s) — total: %d%n", name, roomId, rooms.size());
        bus.emit(new ServerEvent.Builder(EventType.ROOM_CREATED).room(room).build());
        return room;
    }

    /**
     * Factory method — override in a subclass to return your own Room subclass.
     * <pre>
     * &#64;Override
     * protected Room newRoom(String id, String name, int maxPlayers) {
     *     return new LotoRoom(id, name, maxPlayers);
     * }
     * </pre>
     */
    protected Room newRoom(String id, String name, int maxPlayers) {
        return new Room(id, name, maxPlayers);
    }

    /**
     * Closes a room: broadcasts a notification, removes all players, shuts down.
     */
    public synchronized void closeRoom(String roomId) {
        Room room = rooms.remove(roomId);
        if (room == null) return;

        // Notify all players in the room
        String json = OutboundMsg.roomStateChanged(room, room.getState().name(), "CLOSED").toJson();
        broadcastToRoom(room, json, null);

        // Remove players from room
        for (Player p : room.getPlayers()) {
            room.removePlayer(p.getId());
        }

        room.setState(Room.RoomState.CLOSED);
        bus.emit(new ServerEvent.Builder(EventType.ROOM_CLOSED).room(room).build());
        System.out.printf("[RoomManager] Closed room '%s'%n", roomId);
    }

    // ── Player join / leave ───────────────────────────────────────────

    /**
     * Moves a player into a room.
     * @return true on success; false if room full, not found, or player already in it
     */
    public synchronized boolean joinRoom(Player player, String roomId, String password) {
        Room room = rooms.get(roomId);
        if (room == null) return false;
        if (!room.checkPassword(password)) return false;
        if (room.hasPlayer(player.getId())) return false;

        // Leave current room first — permanent=true because player is moving to another room
        if (player.getRoomId() != null) {
            leaveRoom(player, true);
        }

        boolean added = room.addPlayer(player);
        if (!added) return false;

        // Notify room
        String json = OutboundMsg.playerJoined(player).toJson();
        broadcastToRoom(room, json, player.getConnId());

        bus.emit(new ServerEvent.Builder(EventType.ROOM_PLAYER_JOINED)
                .player(player).room(room).build());
        return true;
    }

    /**
     * Removes a player from their current room.
     * @param permanent true = player disconnected for good; false = left voluntarily
     */
    public synchronized boolean leaveRoom(Player player, boolean permanent) {
        String roomId = player.getRoomId();
        if (roomId == null) return false;

        Room room = rooms.get(roomId);
        if (room == null) { player.setRoomId(null); return false; }

        room.removePlayer(player.getId());

        // Notify room
        String json = OutboundMsg.playerLeft(player.getId(), permanent).toJson();
        broadcastToRoom(room, json, null);

        bus.emit(new ServerEvent.Builder(EventType.ROOM_PLAYER_LEFT)
                .player(player).room(room).build());

        // Auto-close empty rooms? (optional — keep for now)
        // if (room.isEmpty()) closeRoom(roomId);
        return true;
    }

    // ── State change ──────────────────────────────────────────────────

    public synchronized void changeRoomState(String roomId, Room.RoomState newState) {
        Room room = rooms.get(roomId);
        if (room == null) return;
        String old = room.getState().name();
        room.setState(newState);
        String json = OutboundMsg.roomStateChanged(room, old, newState.name()).toJson();
        broadcastToRoom(room, json, null);
        bus.emit(new ServerEvent.Builder(EventType.ROOM_STATE_CHANGED).room(room).build());
    }

    // ── Broadcast helpers ─────────────────────────────────────────────

    /**
     * Broadcast a JSON string to all connected players in a room.
     * @param excludeConnId if non-null, skip this connection
     */
    public void broadcastToRoom(Room room, String json, String excludeConnId) {
        for (Player p : room.getPlayers()) {
            if (p.isConnected() && !p.getConnId().equals(excludeConnId)) {
                playerManager.sendTo(p.getConnId(), json);
            }
        }
    }

    public void broadcastToRoom(String roomId, String json, String excludeConnId) {
        Room r = rooms.get(roomId);
        if (r != null) broadcastToRoom(r, json, excludeConnId);
    }

    // ── Accessors ─────────────────────────────────────────────────────

    public Room getRoom(String roomId)           { return rooms.get(roomId); }
    public Collection<Room> listRooms()          { return Collections.unmodifiableCollection(rooms.values()); }
    public int getRoomCount()                    { return rooms.size(); }

    public synchronized void shutdownAll() {
        new ArrayList<>(rooms.keySet()).forEach(this::closeRoom);
    }
}

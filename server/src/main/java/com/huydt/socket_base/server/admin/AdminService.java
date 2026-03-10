package com.huydt.socket_base.server.admin;

import com.huydt.socket_base.server.core.PlayerManager;
import com.huydt.socket_base.server.core.RoomManager;
import com.huydt.socket_base.server.model.Player;
import com.huydt.socket_base.server.model.Room;
import com.huydt.socket_base.server.protocol.OutboundMsg;
import org.json.JSONObject;

import java.util.Collection;

/**
 * Programmatic admin API — the same operations that are available over the
 * network via {@code ADMIN_AUTH} + admin messages, exposed as plain Java methods.
 *
 * <p>Inject this service wherever you need server-side control from your app code.
 *
 * <h3>Usage</h3>
 * <pre>
 * AdminService admin = server.getAdmin();
 *
 * admin.kick("player123", "AFK");
 * admin.broadcastToRoom("room1", "ROUND_START", null);
 * admin.changeRoomState("room1", Room.RoomState.PLAYING);
 * admin.sendTo("player456", "HINT", new JSONObject().put("text", "Try again!"));
 * </pre>
 */
public class AdminService {

    private final PlayerManager playerManager;
    private final RoomManager   roomManager;

    public AdminService(PlayerManager playerManager, RoomManager roomManager) {
        this.playerManager = playerManager;
        this.roomManager   = roomManager;
    }

    // ── Player admin ──────────────────────────────────────────────────

    /** Kick a player (send KICKED message, close connection, remove from room). */
    public void kick(String playerId, String reason) {
        Player p = playerManager.getById(playerId);
        if (p != null && p.getRoomId() != null) roomManager.leaveRoom(p, true);
        playerManager.kick(playerId, reason);
    }

    /** Ban a player by id (also bans their IP). */
    public void ban(String playerId, String reason) {
        Player p = playerManager.getById(playerId);
        if (p != null && p.getRoomId() != null) roomManager.leaveRoom(p, true);
        playerManager.ban(playerId, reason);
    }

    /** Unban a player by display name. */
    public void unban(String name)  { playerManager.unban(name); }

    /** Ban an IP address. */
    public void banIp(String ip)    { playerManager.banIp(ip); }

    /** Unban an IP address. */
    public void unbanIp(String ip)  { playerManager.unbanIp(ip); }

    // ── Room admin ────────────────────────────────────────────────────

    /** Create a new room. */
    public Room createRoom(String name) { return roomManager.createRoom(name); }

    /** Create a room with explicit id and player cap. */
    public Room createRoom(String roomId, String name, int maxPlayers) {
        return roomManager.createRoom(roomId, name, maxPlayers);
    }

    /** Close a room (notifies all players). */
    public void closeRoom(String roomId) { roomManager.closeRoom(roomId); }

    /** Change a room's state and broadcast the change. */
    public void changeRoomState(String roomId, Room.RoomState state) {
        roomManager.changeRoomState(roomId, state);
    }

    // ── Messaging ─────────────────────────────────────────────────────

    /**
     * Broadcast a custom event to all players in a room.
     * @param roomId  target room (null = all players server-wide)
     * @param tag     application event tag
     * @param data    arbitrary payload (null = empty)
     */
    public void broadcast(String roomId, String tag, JSONObject data) {
        String json = OutboundMsg.custom(tag, data).toJson();
        if (roomId != null) {
            roomManager.broadcastToRoom(roomId, json, null);
        } else {
            playerManager.broadcast(json);
        }
    }

    /**
     * Send a custom event to a single player.
     * @param playerId target player id
     * @param tag      application event tag
     * @param data     arbitrary payload (null = empty)
     */
    public void sendTo(String playerId, String tag, JSONObject data) {
        playerManager.sendToPlayer(playerId, OutboundMsg.custom(tag, data).toJson());
    }

    /** Send raw JSON to a player. */
    public void sendRaw(String playerId, String json) {
        playerManager.sendToPlayer(playerId, json);
    }

    /**
     * Push updated player data to the player themselves and all members of their room.
     * Call this whenever a player's game fields change (money, pages, score, ...).
     */
    public void pushPlayerUpdate(Player player) {
        String json = OutboundMsg.playerUpdate(player).toJson();
        // Always send to the player themselves
        if (player.getConnId() != null && player.isConnected()) {
            playerManager.sendTo(player.getConnId(), json);
        }
        // Broadcast to room-mates so they see the updated state too
        if (player.getRoomId() != null) {
            Room room = roomManager.getRoom(player.getRoomId());
            if (room != null) {
                for (Player p : room.getPlayers()) {
                    if (p.isConnected() && !p.getId().equals(player.getId())) {
                        playerManager.sendTo(p.getConnId(), json);
                    }
                }
            }
        }
    }

    /**
     * Push updated room data to all connected members of the room.
     * Call this whenever the room's game fields change (bet, jackpot, round, ...).
     */
    public void pushRoomUpdate(Room room) {
        String json = OutboundMsg.roomUpdate(room).toJson();
        for (Player p : room.getPlayers()) {
            if (p.isConnected()) {
                playerManager.sendTo(p.getConnId(), json);
            }
        }
    }

    /**
     * Push updated room data by roomId.
     */
    public void pushRoomUpdate(String roomId) {
        Room room = roomManager.getRoom(roomId);
        if (room != null) pushRoomUpdate(room);
    }

    // ── Queries ───────────────────────────────────────────────────────

    public Player           getPlayer(String playerId)  { return playerManager.getById(playerId); }
    public Collection<Player> getAllPlayers()            { return playerManager.getAllPlayers(); }
    public Room             getRoom(String roomId)       { return roomManager.getRoom(roomId); }
    public Collection<Room> getAllRooms()                { return roomManager.listRooms(); }
    public int              getPlayerCount()             { return playerManager.getTotalCount(); }
    public int              getRoomCount()               { return roomManager.getRoomCount(); }
}

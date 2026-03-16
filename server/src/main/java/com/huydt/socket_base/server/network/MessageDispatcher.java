package com.huydt.socket_base.server.network;

import com.huydt.socket_base.server.core.PlayerManager;
import com.huydt.socket_base.server.core.RoomManager;
import com.huydt.socket_base.server.event.EventBus;
import com.huydt.socket_base.server.event.EventType;
import com.huydt.socket_base.server.event.ServerEvent;
import com.huydt.socket_base.server.model.Player;
import com.huydt.socket_base.server.model.Room;
import com.huydt.socket_base.server.protocol.InboundMsg;
import com.huydt.socket_base.server.protocol.MsgType;
import com.huydt.socket_base.server.protocol.OutboundMsg;
import org.json.JSONObject;

import java.util.Set;

/**
 * Routes parsed inbound messages to the correct manager action.
 * One dispatcher is shared across all client handler threads.
 *
 * <p>Extend and override {@link #dispatchCustom} to handle CUSTOM messages
 * or add your own message types.
 *
 * <h3>Snapshot strategy</h3>
 * <ul>
 *   <li>Players in the <b>lobby</b> (roomId == null) receive {@code APP_SNAPSHOT}
 *       — full player list + room list.</li>
 *   <li>Players <b>inside a room</b> receive {@code ROOM_SNAPSHOT}
 *       — only their own room's current state.</li>
 * </ul>
 */
public class MessageDispatcher {

    protected final PlayerManager playerManager;
    protected final RoomManager   roomManager;
    protected final EventBus      bus;
    protected final String        adminToken;

    /** connIds that have successfully authenticated as admin. */
    protected final Set<String> adminConns;

    public MessageDispatcher(PlayerManager playerManager,
                             RoomManager   roomManager,
                             EventBus      bus,
                             String        adminToken) {
        this.playerManager = playerManager;
        this.roomManager   = roomManager;
        this.bus           = bus;
        this.adminToken    = adminToken;
        this.adminConns    = java.util.Collections.newSetFromMap(
                new java.util.concurrent.ConcurrentHashMap<>());

        // Hook: fired on the scheduler thread when a ghost player's reconnect
        // timer expires and they are permanently removed.
        playerManager.onPermanentRemove = removedPlayer -> {
            if (removedPlayer.getRoomId() != null) {
                Room room = roomManager.getRoom(removedPlayer.getRoomId());
                if (room != null) {
                    room.removePlayer(removedPlayer.getId());
                    roomManager.broadcastToRoom(room,
                            OutboundMsg.playerLeft(removedPlayer.getId(), true).toJson(), null);
                }
            }
            notifyLobby(null);
        };
    }

    // ── Main dispatch ─────────────────────────────────────────────────

    public void dispatch(String connId, InboundMsg msg, IClientHandler handler) {
        bus.emit(new ServerEvent.Builder(EventType.MESSAGE_RECEIVED)
                .message(msg.getType().name())
                .data("connId", connId).build());

        switch (msg.getType()) {
            case JOIN:       handleJoin(connId, msg, handler);      break;
            case RECONNECT:  handleReconnect(connId, msg, handler); break;
            case JOIN_ROOM:  handleJoinRoom(connId, msg, handler);  break;
            case LEAVE_ROOM: handleLeaveRoom(connId, handler);      break;
            case ADMIN_AUTH: handleAdminAuth(connId, msg, handler); break;

            // ── Admin-only ────────────────────────────────────────────
            case KICK:            requireAdmin(connId, handler, () -> handleKick(connId, msg, handler));           break;
            case BAN:             requireAdmin(connId, handler, () -> handleBan(connId, msg, handler));            break;
            case UNBAN:           requireAdmin(connId, handler, () -> handleUnban(connId, msg, handler));          break;
            case BAN_IP:          requireAdmin(connId, handler, () -> handleBanIp(connId, msg, handler));          break;
            case UNBAN_IP:        requireAdmin(connId, handler, () -> handleUnbanIp(connId, msg, handler));        break;
            case GET_BAN_LIST:    requireAdmin(connId, handler, () -> handleGetBanList(handler));                  break;
            case CREATE_ROOM:     requireAdmin(connId, handler, () -> handleCreateRoom(connId, msg, handler));     break;
            case CLOSE_ROOM:      requireAdmin(connId, handler, () -> handleCloseRoom(connId, msg, handler));      break;
            case SET_ROOM_STATE:  requireAdmin(connId, handler, () -> handleSetRoomState(connId, msg, handler));   break;
            case LIST_ROOMS:      requireAdmin(connId, handler, () -> handleListRooms(handler));                   break;
            case GET_ROOM:        requireAdmin(connId, handler, () -> handleGetRoom(connId, msg, handler));        break;
            case ADMIN_BROADCAST: requireAdmin(connId, handler, () -> handleAdminBroadcast(connId, msg, handler)); break;
            case ADMIN_SEND:      requireAdmin(connId, handler, () -> handleAdminSend(connId, msg, handler));      break;
            case GET_STATS:       requireAdmin(connId, handler, () -> handleGetStats(handler));                    break;

            case CUSTOM: dispatchCustom(connId, msg, handler); break;

            default:
                handler.send(OutboundMsg.error("UNKNOWN_TYPE",
                        "Unhandled message type: " + msg.getType()).toJson());
        }
    }

    public void onDisconnected(String connId) {
        adminConns.remove(connId);

        Player p = playerManager.getByConnId(connId);
        if (p != null && p.getRoomId() != null) {
            Room room = roomManager.getRoom(p.getRoomId());
            if (room != null) {
                roomManager.broadcastToRoom(room,
                        OutboundMsg.playerLeft(p.getId(), false).toJson(), connId);
            }
        }

        playerManager.onDisconnected(connId);
        notifyLobby(null);
    }

    // ── Handlers ──────────────────────────────────────────────────────

    private void handleJoin(String connId, InboundMsg msg, IClientHandler handler) {
        // IP ban check
        if (playerManager.isBanned(null, handler.getRemoteIp())) {
            handler.send(OutboundMsg.banned("Your IP is banned").toJson());
            handler.close();
            return;
        }

        // Reconnect path — client sends their previous token
        String token = msg.getString("token");
        if (token != null && !token.isEmpty()) {
            Player p = playerManager.reconnect(connId, token, handler);
            if (p != null) {
                completeReconnect(connId, p, handler);
                return;
            }
            // Token not found / expired → fall through to fresh join
        }

        // Fresh join path
        String name = msg.getString("name");
        if (name == null || name.trim().isEmpty()) {
            handler.send(OutboundMsg.error("MISSING_NAME", "name is required to join").toJson());
            return;
        }

        Player player = playerManager.join(connId, name.trim(), handler);

        // Resolve room to join
        Room room = null;
        String roomId = msg.getString("roomId");
        if (roomId != null && !roomId.isEmpty()) {
            if (roomManager.getRoom(roomId) != null) {
                roomManager.joinRoom(player, roomId, msg.getString("password"));
                room = roomManager.getRoom(roomId);
            }
        } else if (roomManager.getRoomCount() == 1) {
            // Auto-join the only room
            room = roomManager.getAllRooms().iterator().next();
            roomManager.joinRoom(player, room.getId(), null);
            room = roomManager.getRoom(room.getId());
        }

        handler.send(OutboundMsg.welcome(player, room).toJson());
        sendSnapshotTo(handler, room);
        notifyLobby(connId);
    }

    private void handleReconnect(String connId, InboundMsg msg, IClientHandler handler) {
        String token = msg.getString("token");
        if (token == null) {
            handler.send(OutboundMsg.error("MISSING_TOKEN", "token is required").toJson());
            return;
        }

        Player p = playerManager.reconnect(connId, token, handler);
        if (p == null) {
            handler.send(OutboundMsg.error("INVALID_TOKEN", "Token not found or expired").toJson());
            return;
        }

        completeReconnect(connId, p, handler);
    }

    /**
     * Shared post-reconnect logic used by both {@link #handleJoin} (token path)
     * and {@link #handleReconnect}.
     */
    private void completeReconnect(String connId, Player p, IClientHandler handler) {
        Room room = p.getRoomId() != null ? roomManager.getRoom(p.getRoomId()) : null;
        handler.send(OutboundMsg.reconnected(p, room).toJson());
        sendSnapshotTo(handler, room);

        if (room != null) {
            roomManager.broadcastToRoom(room, OutboundMsg.playerReconnected(p).toJson(), connId);
            notifyRoom(room, connId);
        }
        notifyLobby(connId);
    }

    private void handleJoinRoom(String connId, InboundMsg msg, IClientHandler handler) {
        Player player = playerManager.getByConnId(connId);
        if (player == null) {
            handler.send(OutboundMsg.error("NOT_JOINED", "Send JOIN first").toJson()); return;
        }

        String roomId = msg.getString("roomId");
        if (roomId == null) {
            handler.send(OutboundMsg.error("MISSING_ROOM_ID", "roomId required").toJson()); return;
        }

        boolean ok = roomManager.joinRoom(player, roomId, msg.getString("password"));
        if (!ok) {
            handler.send(OutboundMsg.error("JOIN_FAILED",
                    "Room not found, full, or wrong password").toJson());
            return;
        }

        sendSnapshotTo(handler, roomManager.getRoom(roomId));
        notifyLobby(connId);
    }

    private void handleLeaveRoom(String connId, IClientHandler handler) {
        Player player = playerManager.getByConnId(connId);
        if (player == null || player.getRoomId() == null) return;

        roomManager.leaveRoom(player, true);
        sendSnapshotTo(handler, null);
        notifyLobby(connId);
    }

    private void handleAdminAuth(String connId, InboundMsg msg, IClientHandler handler) {
        String token = msg.getString("token");
        if (adminToken == null || !adminToken.equals(token)) {
            handler.send(OutboundMsg.error("AUTH_FAILED", "Invalid admin token").toJson());
            return;
        }

        // Kick out any existing admin connections
        for (String oldConnId : new java.util.ArrayList<>(adminConns)) {
            if (oldConnId.equals(connId)) continue;
            replaceAdminConn(oldConnId);
        }

        adminConns.add(connId);
        Player p = playerManager.getByConnId(connId);
        if (p != null) p.setAdmin(true);

        handler.send(OutboundMsg.adminAuthOk().toJson());
        bus.emit(new ServerEvent.Builder(EventType.ADMIN_AUTH)
                .message("connId=" + connId).build());
    }

    private void replaceAdminConn(String oldConnId) {
        IClientHandler oldHandler = playerManager.getHandler(oldConnId);
        if (oldHandler != null) {
            try {
                oldHandler.send(OutboundMsg.error("REPLACED",
                        "Admin session was taken over by a new connection").toJson());
                oldHandler.close();
            } catch (Exception e) {
                System.err.println("[Dispatcher] Failed to close old admin conn: " + e.getMessage());
            }
        }
        adminConns.remove(oldConnId);
        Player oldPlayer = playerManager.getByConnId(oldConnId);
        if (oldPlayer != null) oldPlayer.setAdmin(false);
        System.out.printf("[Dispatcher] Replaced old admin connection '%s'%n", oldConnId);
    }

    // ── Admin handlers ────────────────────────────────────────────────

    private void handleKick(String connId, InboundMsg msg, IClientHandler handler) {
        String playerId = requireField(msg, "playerId", handler);
        if (playerId == null) return;
        Player target = playerManager.getById(playerId);
        if (target != null && target.getRoomId() != null) roomManager.leaveRoom(target, true);
        playerManager.kick(playerId, msg.getString("reason"));
    }

    private void handleBan(String connId, InboundMsg msg, IClientHandler handler) {
        String playerId = requireField(msg, "playerId", handler);
        if (playerId == null) return;
        Player target = playerManager.getById(playerId);
        if (target != null && target.getRoomId() != null) roomManager.leaveRoom(target, true);
        playerManager.ban(playerId, msg.getString("reason"));
    }

    private void handleUnban(String connId, InboundMsg msg, IClientHandler handler) {
        String name = requireField(msg, "name", handler);
        if (name == null) return;
        playerManager.unban(name);
    }

    private void handleBanIp(String connId, InboundMsg msg, IClientHandler handler) {
        String ip = requireField(msg, "ip", handler);
        if (ip == null) return;
        playerManager.banIp(ip);
    }

    private void handleUnbanIp(String connId, InboundMsg msg, IClientHandler handler) {
        String ip = requireField(msg, "ip", handler);
        if (ip == null) return;
        playerManager.unbanIp(ip);
    }

    private void handleGetBanList(IClientHandler handler) {
        handler.send(OutboundMsg.banList(playerManager.getBannedIds(), playerManager.getBannedIps()).toJson());
    }

    private void handleCreateRoom(String connId, InboundMsg msg, IClientHandler handler) {
        String name = requireField(msg, "name", handler);
        if (name == null) return;
        try {
            Room room = roomManager.createRoom(name);
            handler.send(OutboundMsg.roomInfo(room).toJson());
            // Notify everyone: lobby players see a new room, room players see updated room list
            notifyAll(null);
        } catch (Exception e) {
            handler.send(OutboundMsg.error("CREATE_FAILED", e.getMessage()).toJson());
        }
    }

    private void handleCloseRoom(String connId, InboundMsg msg, IClientHandler handler) {
        String roomId = requireField(msg, "roomId", handler);
        if (roomId == null) return;
        roomManager.closeRoom(roomId);
        // Notify everyone: lobby + in-room players see updated room list
        notifyAll(null);
    }

    private void handleSetRoomState(String connId, InboundMsg msg, IClientHandler handler) {
        String roomId = requireField(msg, "roomId", handler);
        String state  = requireField(msg, "state",  handler);
        if (roomId == null || state == null) return;
        try {
            roomManager.changeRoomState(roomId, Room.RoomState.valueOf(state.toUpperCase()));
        } catch (IllegalArgumentException e) {
            handler.send(OutboundMsg.error("INVALID_STATE", "Unknown state: " + state).toJson());
        }
    }

    private void handleListRooms(IClientHandler handler) {
        handler.send(OutboundMsg.roomList(roomManager.getAllRooms()).toJson());
    }

    private void handleGetRoom(String connId, InboundMsg msg, IClientHandler handler) {
        String roomId = requireField(msg, "roomId", handler);
        if (roomId == null) return;
        Room room = roomManager.getRoom(roomId);
        if (room == null) { handler.send(OutboundMsg.error("NOT_FOUND", "Room not found").toJson()); return; }
        handler.send(OutboundMsg.roomInfo(room).toJson());
    }

    private void handleAdminBroadcast(String connId, InboundMsg msg, IClientHandler handler) {
        String roomId  = msg.getString("roomId");
        JSONObject data = msg.getObject("data");
        String tag     = msg.getString("tag", "ADMIN_MSG");
        String json    = OutboundMsg.custom(tag, data).toJson();
        if (roomId != null) {
            roomManager.broadcastToRoom(roomId, json, null);
        } else {
            playerManager.broadcast(json);
        }
    }

    private void handleAdminSend(String connId, InboundMsg msg, IClientHandler handler) {
        String playerId = requireField(msg, "playerId", handler);
        if (playerId == null) return;
        JSONObject data = msg.getObject("data");
        String tag      = msg.getString("tag", "ADMIN_MSG");
        playerManager.sendToPlayer(playerId, OutboundMsg.custom(tag, data).toJson());
    }

    private void handleGetStats(IClientHandler handler) {
        handler.send(OutboundMsg.stats(
                roomManager.getRoomCount(),
                playerManager.getTotalCount(),
                System.currentTimeMillis()).toJson());
    }

    // ── Extension point ───────────────────────────────────────────────

    /**
     * Override this in subclasses to handle CUSTOM messages and any
     * application-specific message types.
     *
     * <pre>
     * &#64;Override
     * protected void dispatchCustom(String connId, InboundMsg msg, IClientHandler handler) {
     *     String tag = msg.getString("tag");
     *     if ("BUY_PAGE".equals(tag)) { ... }
     * }
     * </pre>
     */
    protected void dispatchCustom(String connId, InboundMsg msg, IClientHandler handler) {
        bus.emit(new ServerEvent.Builder(EventType.CUSTOM)
                .message(msg.getString("tag"))
                .data("connId", connId)
                .data("payload", msg.getPayload())
                .build());
    }

    // ── Snapshot helpers ──────────────────────────────────────────────

    protected void sendSnapshotTo(IClientHandler handler, Room room) {
        if (room != null) {
            handler.send(OutboundMsg.roomSnapshot(room).toJson());
        } else {
            handler.send(OutboundMsg.appSnapshot(
                    playerManager.getAllPlayers(),
                    roomManager.getAllRooms()).toJson());
        }
    }

    protected void notifyLobby(String excludeConnId) {
        String appSnapshotJson = OutboundMsg.appSnapshot(
                playerManager.getAllPlayers(),
                roomManager.getAllRooms()).toJson();

        for (Player p : playerManager.getConnectedPlayers()) {
            if (p.getConnId() == null) continue;
            if (p.getConnId().equals(excludeConnId)) continue;
            if (p.getRoomId() == null) {
                playerManager.sendTo(p.getConnId(), appSnapshotJson);
            }
        }
    }

    /**
     * Send {@code APP_SNAPSHOT} to ALL connected players (lobby + in-room).
     * Use this after events that change the global room list (create/close room),
     * so players inside a room can also see the updated room count/list in their UI.
     *
     * @param excludeConnId skip this connection — null = include everyone
     */
    protected void notifyAll(String excludeConnId) {
        String appSnapshotJson = OutboundMsg.appSnapshot(
                playerManager.getAllPlayers(),
                roomManager.getAllRooms()).toJson();

        for (Player p : playerManager.getConnectedPlayers()) {
            if (p.getConnId() == null) continue;
            if (p.getConnId().equals(excludeConnId)) continue;
            playerManager.sendTo(p.getConnId(), appSnapshotJson);
        }
    }

    protected void notifyRoom(Room room, String excludeConnId) {
        String json = OutboundMsg.roomSnapshot(room).toJson();
        for (Player p : room.getPlayers()) {
            if (p.isConnected() && !p.getConnId().equals(excludeConnId)) {
                playerManager.sendTo(p.getConnId(), json);
            }
        }
    }

    // ── Auth helpers ──────────────────────────────────────────────────

    protected boolean isAdmin(String connId) {
        return adminConns.contains(connId);
    }

    protected void requireAdmin(String connId, IClientHandler handler, Runnable action) {
        if (!isAdmin(connId)) {
            handler.send(OutboundMsg.error("NOT_ADMIN",
                    "Admin authentication required. Send ADMIN_AUTH first.").toJson());
            return;
        }
        action.run();
    }

    // ── Field validation helper ───────────────────────────────────────

    /**
     * Returns the field value if present, otherwise sends an error and returns null.
     */
    private String requireField(InboundMsg msg, String field, IClientHandler handler) {
        String value = msg.getString(field);
        if (value == null) {
            handler.send(OutboundMsg.error("MISSING_FIELDS", field + " required").toJson());
        }
        return value;
    }
}

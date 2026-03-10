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
                    // Clients already track members — just tell them who left permanently
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
        String disconnectedFromRoom = p != null ? p.getRoomId() : null;

        if (disconnectedFromRoom != null) {
            Room room = roomManager.getRoom(disconnectedFromRoom);
            if (room != null) {
                // Player becomes a ghost — room membership kept until reconnect timeout.
                roomManager.broadcastToRoom(room,
                        OutboundMsg.playerLeft(p.getId(), false).toJson(), connId);
            }
        }

        playerManager.onDisconnected(connId);

        // Only lobby players need APP_SNAPSHOT (player count changed).
        // Room members already got PLAYER_LEFT above — no ROOM_SNAPSHOT needed
        // because the ghost is still "in" the room until timeout.
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

        String token = msg.getString("token");

        // Reconnect path — client sends their previous token
        if (token != null && !token.isEmpty()) {
            Player p = playerManager.reconnect(connId, token, handler);
            if (p != null) {
                Room room = p.getRoomId() != null ? roomManager.getRoom(p.getRoomId()) : null;

                handler.send(OutboundMsg.reconnected(p, room).toJson());
                sendSnapshotTo(handler, room);

                if (room != null) {
                    roomManager.broadcastToRoom(room,
                            OutboundMsg.playerReconnected(p).toJson(), connId);
                    // Room members already got PLAYER_RECONNECTED + will get notifyRoom below.
                    // Other rooms are unaffected.
                    notifyRoom(room, connId);
                }
                notifyLobby(connId);
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
        String roomId = msg.getString("roomId");
        Room room = null;

        if (roomId != null && !roomId.isEmpty()) {
            room = roomManager.getRoom(roomId);
            if (room != null) {
                roomManager.joinRoom(player, roomId, msg.getString("password"));
                room = roomManager.getRoom(roomId); // re-fetch with updated player list
            }
        } else if (roomManager.getRoomCount() == 1) {
            // Auto-join the only room
            room = roomManager.listRooms().iterator().next();
            roomManager.joinRoom(player, room.getId(), null);
            room = roomManager.getRoom(room.getId());
        }

        handler.send(OutboundMsg.welcome(player, room).toJson());
        sendSnapshotTo(handler, room);

        // RoomManager.joinRoom already broadcast PLAYER_JOINED to room members.
        // Room members update their local list from that event — no ROOM_SNAPSHOT needed.

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

        Room room = p.getRoomId() != null ? roomManager.getRoom(p.getRoomId()) : null;
        handler.send(OutboundMsg.reconnected(p, room).toJson());
        sendSnapshotTo(handler, room);

        if (room != null) {
            // Tell room-mates this ghost came back
            roomManager.broadcastToRoom(room, OutboundMsg.playerReconnected(p).toJson(), connId);
            // Room members need ROOM_SNAPSHOT because the ghost's connected status changed
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

        // Capture old room before joinRoom changes player.roomId
        String oldRoomId = player.getRoomId();

        boolean ok = roomManager.joinRoom(player, roomId, msg.getString("password"));
        if (!ok) {
            handler.send(OutboundMsg.error("JOIN_FAILED",
                    "Room not found, full, or wrong password").toJson());
            return;
        }

        Room newRoom = roomManager.getRoom(roomId);
        // Send the joining player a full room snapshot (their first view of this room)
        sendSnapshotTo(handler, newRoom);

        // RoomManager.joinRoom already broadcast:
        //   - PLAYER_LEFT  to old room members  (if player switched rooms)
        //   - PLAYER_JOINED to new room members
        // Clients update their local lists from those events — no ROOM_SNAPSHOT needed.

        notifyLobby(connId);
    }

    private void handleLeaveRoom(String connId, IClientHandler handler) {
        Player player = playerManager.getByConnId(connId);
        if (player == null || player.getRoomId() == null) return;

        roomManager.leaveRoom(player, true); // true = left for good (voluntary)

        // RoomManager.leaveRoom already broadcast PLAYER_LEFT(permanent=true) to room members.

        sendSnapshotTo(handler, null);
        notifyLobby(connId);
    }

    private void handleAdminAuth(String connId, InboundMsg msg, IClientHandler handler) {
        String token = msg.getString("token");
        if (adminToken == null || !adminToken.equals(token)) {
            handler.send(OutboundMsg.error("AUTH_FAILED", "Invalid admin token").toJson());
            return;
        }

        // Kick out any existing admin connection using the same token
        for (String oldConnId : new java.util.ArrayList<>(adminConns)) {
            if (oldConnId.equals(connId)) continue;
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

        adminConns.add(connId);
        Player p = playerManager.getByConnId(connId);
        if (p != null) p.setAdmin(true);

        handler.send(OutboundMsg.adminAuthOk().toJson());
        bus.emit(new ServerEvent.Builder(EventType.ADMIN_AUTH)
                .message("connId=" + connId).build());
    }

    private void handleKick(String connId, InboundMsg msg, IClientHandler handler) {
        String playerId = msg.getString("playerId");
        if (playerId == null) { handler.send(OutboundMsg.error("MISSING_FIELDS", "playerId required").toJson()); return; }
        Player target = playerManager.getById(playerId);
        if (target != null && target.getRoomId() != null) roomManager.leaveRoom(target, true);
        playerManager.kick(playerId, msg.getString("reason"));
    }

    private void handleBan(String connId, InboundMsg msg, IClientHandler handler) {
        String playerId = msg.getString("playerId");
        if (playerId == null) { handler.send(OutboundMsg.error("MISSING_FIELDS", "playerId required").toJson()); return; }
        Player target = playerManager.getById(playerId);
        if (target != null && target.getRoomId() != null) roomManager.leaveRoom(target, true);
        playerManager.ban(playerId, msg.getString("reason"));
    }

    private void handleUnban(String connId, InboundMsg msg, IClientHandler handler) {
        String name = msg.getString("name");
        if (name == null) { handler.send(OutboundMsg.error("MISSING_FIELDS", "name required").toJson()); return; }
        playerManager.unban(name);
    }

    private void handleBanIp(String connId, InboundMsg msg, IClientHandler handler) {
        String ip = msg.getString("ip");
        if (ip == null) { handler.send(OutboundMsg.error("MISSING_FIELDS", "ip required").toJson()); return; }
        playerManager.banIp(ip);
    }

    private void handleUnbanIp(String connId, InboundMsg msg, IClientHandler handler) {
        String ip = msg.getString("ip");
        if (ip == null) { handler.send(OutboundMsg.error("MISSING_FIELDS", "ip required").toJson()); return; }
        playerManager.unbanIp(ip);
    }

    private void handleGetBanList(IClientHandler handler) {
        handler.send(OutboundMsg.banList(playerManager.getBannedIds(), playerManager.getBannedIps()).toJson());
    }

    private void handleCreateRoom(String connId, InboundMsg msg, IClientHandler handler) {
        String name = msg.getString("name");
        if (name == null) { handler.send(OutboundMsg.error("MISSING_FIELDS", "name required").toJson()); return; }
        try {
            Room room = roomManager.createRoom(name);
            handler.send(OutboundMsg.roomInfo(room).toJson());
            notifyLobby(null); // new room visible to lobby
        } catch (Exception e) {
            handler.send(OutboundMsg.error("CREATE_FAILED", e.getMessage()).toJson());
        }
    }

    private void handleCloseRoom(String connId, InboundMsg msg, IClientHandler handler) {
        String roomId = msg.getString("roomId");
        if (roomId == null) { handler.send(OutboundMsg.error("MISSING_FIELDS", "roomId required").toJson()); return; }
        roomManager.closeRoom(roomId);
        notifyLobby(null); // room gone, lobby sees updated list
    }

    private void handleSetRoomState(String connId, InboundMsg msg, IClientHandler handler) {
        String roomId = msg.getString("roomId");
        String state  = msg.getString("state");
        if (roomId == null || state == null) {
            handler.send(OutboundMsg.error("MISSING_FIELDS", "roomId and state required").toJson()); return;
        }
        try {
            Room.RoomState rs = Room.RoomState.valueOf(state.toUpperCase());
            roomManager.changeRoomState(roomId, rs);
        } catch (IllegalArgumentException e) {
            handler.send(OutboundMsg.error("INVALID_STATE", "Unknown state: " + state).toJson());
        }
    }

    private void handleListRooms(IClientHandler handler) {
        handler.send(OutboundMsg.roomList(roomManager.listRooms()).toJson());
    }

    private void handleGetRoom(String connId, InboundMsg msg, IClientHandler handler) {
        String roomId = msg.getString("roomId");
        if (roomId == null) { handler.send(OutboundMsg.error("MISSING_FIELDS", "roomId required").toJson()); return; }
        Room room = roomManager.getRoom(roomId);
        if (room == null) { handler.send(OutboundMsg.error("NOT_FOUND", "Room not found").toJson()); return; }
        handler.send(OutboundMsg.roomInfo(room).toJson());
    }

    private void handleAdminBroadcast(String connId, InboundMsg msg, IClientHandler handler) {
        String roomId = msg.getString("roomId");
        JSONObject data = msg.getObject("data");
        String tag = msg.getString("tag", "ADMIN_MSG");
        String json = OutboundMsg.custom(tag, data).toJson();
        if (roomId != null) {
            roomManager.broadcastToRoom(roomId, json, null);
        } else {
            playerManager.broadcast(json);
        }
    }

    private void handleAdminSend(String connId, InboundMsg msg, IClientHandler handler) {
        String playerId = msg.getString("playerId");
        JSONObject data = msg.getObject("data");
        String tag = msg.getString("tag", "ADMIN_MSG");
        if (playerId == null) { handler.send(OutboundMsg.error("MISSING_FIELDS", "playerId required").toJson()); return; }
        playerManager.sendToPlayer(playerId, OutboundMsg.custom(tag, data).toJson());
    }

    private void handleGetStats(IClientHandler handler) {
        long uptime = System.currentTimeMillis();
        handler.send(OutboundMsg.stats(
                roomManager.getRoomCount(),
                playerManager.getTotalCount(),
                uptime).toJson());
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

    /**
     * Send the correct snapshot directly to one client:
     * <ul>
     *   <li>In a room → {@code ROOM_SNAPSHOT}</li>
     *   <li>In lobby  → {@code APP_SNAPSHOT}</li>
     * </ul>
     */
    protected void sendSnapshotTo(IClientHandler handler, Room room) {
        if (room != null) {
            handler.send(OutboundMsg.roomSnapshot(room).toJson());
        } else {
            handler.send(OutboundMsg.appSnapshot(
                    playerManager.getAllPlayers(),
                    roomManager.listRooms()).toJson());
        }
    }

    /**
     * Send {@code APP_SNAPSHOT} to all lobby players (roomId == null).
     * Call this after any event that changes the global player/room list
     * (join, leave, disconnect, create/close room).
     *
     * @param excludeConnId skip this connection — null = include everyone
     */
    protected void notifyLobby(String excludeConnId) {
        String appSnapshotJson = OutboundMsg.appSnapshot(
                playerManager.getAllPlayers(),
                roomManager.listRooms()).toJson();

        for (Player p : playerManager.getConnectedPlayers()) {
            if (p.getConnId() == null) continue;
            if (p.getConnId().equals(excludeConnId)) continue;
            if (p.getRoomId() == null) {
                playerManager.sendTo(p.getConnId(), appSnapshotJson);
            }
        }
    }

    /**
     * Send {@code ROOM_SNAPSHOT} to all connected members of one specific room.
     * Call this only when that room's state actually changed.
     *
     * @param room          the room whose members should be notified
     * @param excludeConnId skip this connection — null = include everyone in the room
     */
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
}

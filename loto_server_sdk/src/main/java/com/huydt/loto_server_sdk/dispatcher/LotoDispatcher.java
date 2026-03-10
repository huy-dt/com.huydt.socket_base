package com.huydt.loto_server_sdk.dispatcher;

import com.huydt.loto_server_sdk.LotoGameEngine;
import com.huydt.loto_server_sdk.model.LotoPage;
import com.huydt.loto_server_sdk.model.LotoPlayer;
import com.huydt.loto_server_sdk.model.LotoRoom;
import com.huydt.socket_base.server.admin.AdminService;
import com.huydt.socket_base.server.core.PlayerManager;
import com.huydt.socket_base.server.core.RoomManager;
import com.huydt.socket_base.server.event.EventBus;
import com.huydt.socket_base.server.event.EventType;
import com.huydt.socket_base.server.model.Player;
import com.huydt.socket_base.server.model.Room;
import com.huydt.socket_base.server.network.IClientHandler;
import com.huydt.socket_base.server.network.MessageDispatcher;
import com.huydt.socket_base.server.protocol.InboundMsg;
import com.huydt.socket_base.server.protocol.OutboundMsg;
import org.json.JSONObject;

import java.util.Map;
import java.util.concurrent.ConcurrentHashMap;

/**
 * Routes CUSTOM messages for the Loto game.
 *
 * AdminService is injected after server start via {@link #setAdminService(AdminService)}.
 */
public class LotoDispatcher extends MessageDispatcher {

    // Injected by LotoServer.onServerStarted()
    private AdminService admin;

    // One engine per room — created lazily once AdminService is available
    private final Map<String, LotoGameEngine> engines = new ConcurrentHashMap<>();

    public LotoDispatcher(PlayerManager pm, RoomManager rm,
                          EventBus bus, String adminToken) {
        super(pm, rm, bus, adminToken);

        bus.on(EventType.ROOM_CREATED, e -> {
            if (admin != null) createEngine((LotoRoom) e.getRoom());
        });

        bus.on(EventType.ROOM_CLOSED, e -> {
            LotoGameEngine eng = engines.remove(e.getRoom().getId());
            if (eng != null) eng.shutdown();
        });
    }

    /** Called by LotoServer.onServerStarted() — must be called before any game action. */
    public void setAdminService(AdminService admin) {
        this.admin = admin;
        // Create engines for any rooms that were created before this was called
        for (Room r : roomManager.getAllRooms()) {
            if (r instanceof LotoRoom && !engines.containsKey(r.getId())) {
                createEngine((LotoRoom) r);
            }
        }
    }

    private void createEngine(LotoRoom room) {
        engines.put(room.getId(), new LotoGameEngine(room, roomManager, admin));
    }

    private LotoGameEngine engineFor(String roomId) {
        return engines.computeIfAbsent(roomId, id -> {
            if (admin == null) return null;
            LotoRoom room = (LotoRoom) roomManager.getRoom(id);
            if (room == null) return null;
            return new LotoGameEngine(room, roomManager, admin);
        });
    }

    // ---------------------------------------------------------------- dispatch

    @Override
    protected void dispatchCustom(String connId, InboundMsg msg, IClientHandler handler) {
        Player base = playerManager.getByConnId(connId);
        if (base == null) return;

        LotoPlayer player = (LotoPlayer) base;
        String tag        = msg.getString("tag");
        JSONObject data   = msg.getObject("data");

        switch (tag != null ? tag : "") {
            case "BUY_PAGE":   handleBuyPage(player, data, handler);  break;
            case "START_GAME": handleStartGame(player, handler);       break;
            case "CLAIM_WIN":  handleClaimWin(player, handler);        break;
            case "RESET_ROOM": handleResetRoom(player, handler);       break;
            default:
                handler.send(OutboundMsg.error("UNKNOWN_TAG",
                        "Unknown tag: " + tag).toJson());
        }
    }

    // ---------------------------------------------------------------- handlers

    private void handleBuyPage(LotoPlayer player, JSONObject data, IClientHandler handler) {
        String roomId = player.getRoomId();
        if (roomId == null) {
            handler.send(OutboundMsg.error("NOT_IN_ROOM", "Join a room first").toJson());
            return;
        }
        LotoRoom room = (LotoRoom) roomManager.getRoom(roomId);
        if (room == null) {
            handler.send(OutboundMsg.error("ROOM_NOT_FOUND", "Room not found").toJson());
            return;
        }
        if (room.getState() != Room.RoomState.WAITING) {
            handler.send(OutboundMsg.error("GAME_IN_PROGRESS",
                    "Cannot buy pages while game is in progress").toJson());
            return;
        }
        long price = room.pricePerPage;
        if (player.money < price) {
            handler.send(OutboundMsg.error("INSUFFICIENT_FUNDS",
                    "Need " + price + ", have " + player.money).toJson());
            return;
        }

        player.money -= price;
        room.jackpot += price;
        player.addPage(new LotoPage(player.getId()));

        roomManager.broadcastToRoom(roomId,
                OutboundMsg.playerUpdate(player).toJson(), null);
        notifyRoom(room, null);

        admin.broadcast(roomId, "JACKPOT_UPDATE",
                new JSONObject().put("jackpot", room.jackpot));

        System.out.println("[BUY_PAGE] " + player.getName()
                + " pages=" + player.getPageCount()
                + " money=" + player.money
                + " jackpot=" + room.jackpot);

        LotoGameEngine engine = engineFor(roomId);
        if (engine != null) engine.scheduleAutoStart();
    }

    private void handleStartGame(LotoPlayer player, IClientHandler handler) {
        String roomId = player.getRoomId();
        if (roomId == null) {
            handler.send(OutboundMsg.error("NOT_IN_ROOM", "Join a room first").toJson());
            return;
        }
        LotoRoom room = (LotoRoom) roomManager.getRoom(roomId);
        if (room == null) {
            handler.send(OutboundMsg.error("ROOM_NOT_FOUND", "Room not found").toJson());
            return;
        }
        if (room.getState() != Room.RoomState.WAITING) {
            handler.send(OutboundMsg.error("ALREADY_STARTED", "Game already started").toJson());
            return;
        }

        LotoGameEngine engine = engineFor(roomId);
        if (engine != null) engine.startRound();
    }

    private void handleClaimWin(LotoPlayer player, IClientHandler handler) {
        String roomId = player.getRoomId();
        if (roomId == null) {
            handler.send(OutboundMsg.error("NOT_IN_ROOM", "Join a room first").toJson());
            return;
        }
        LotoGameEngine engine = engineFor(roomId);
        if (engine == null) {
            handler.send(OutboundMsg.error("ROOM_NOT_FOUND", "Room not found").toJson());
            return;
        }
        boolean won = engine.checkBingo(player);
        System.out.println("[CLAIM_WIN] " + player.getName()
                + " → " + (won ? "VALID ✓" : "INVALID ✗"));
    }

    private void handleResetRoom(LotoPlayer player, IClientHandler handler) {
        if (!isAdmin(player.getConnId())) {
            handler.send(OutboundMsg.error("FORBIDDEN", "Admin only").toJson());
            return;
        }
        String roomId = player.getRoomId();
        if (roomId == null) {
            handler.send(OutboundMsg.error("NOT_IN_ROOM", "Join a room first").toJson());
            return;
        }
        LotoGameEngine engine = engineFor(roomId);
        if (engine != null) engine.reset();
    }
}

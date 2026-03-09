package com.huydt.socket_base.util;

import com.huydt.socket_base.core.PlayerManager;
import com.huydt.socket_base.core.RoomManager;
import com.huydt.socket_base.model.Player;
import com.huydt.socket_base.model.Room;
import com.huydt.socket_base.protocol.OutboundMsg;
import org.json.JSONObject;

import java.util.Collection;

/**
 * Static broadcast helpers for common patterns.
 *
 * <h3>Usage</h3>
 * <pre>
 * Broadcast.toRoom(roomManager, room, "ROUND_ENDED", payload);
 * Broadcast.toAll(playerManager, "SERVER_MSG", new JSONObject().put("text", "Maintenance soon"));
 * Broadcast.toPlayers(subset, "BONUS", payload, playerManager);
 * </pre>
 */
public final class Broadcast {

    private Broadcast() {}

    /** Broadcast a custom event to all connected players in a room. */
    public static void toRoom(RoomManager rm, Room room, String tag, JSONObject data) {
        rm.broadcastToRoom(room, OutboundMsg.custom(tag, data).toJson(), null);
    }

    /** Broadcast a custom event to all connected players in a room (by id). */
    public static void toRoom(RoomManager rm, String roomId, String tag, JSONObject data) {
        rm.broadcastToRoom(roomId, OutboundMsg.custom(tag, data).toJson(), null);
    }

    /** Broadcast a custom event to all players server-wide. */
    public static void toAll(PlayerManager pm, String tag, JSONObject data) {
        pm.broadcast(OutboundMsg.custom(tag, data).toJson());
    }

    /** Send a custom event to a specific player by id. */
    public static void toPlayer(PlayerManager pm, String playerId, String tag, JSONObject data) {
        pm.sendToPlayer(playerId, OutboundMsg.custom(tag, data).toJson());
    }

    /** Broadcast raw JSON to all players in a room. */
    public static void rawToRoom(RoomManager rm, Room room, String json) {
        rm.broadcastToRoom(room, json, null);
    }

    /** Broadcast raw JSON to all players. */
    public static void rawToAll(PlayerManager pm, String json) {
        pm.broadcast(json);
    }

    /** Send to a specific subset of players. */
    public static void toPlayers(Collection<Player> players, String tag, JSONObject data,
                                  PlayerManager pm) {
        String json = OutboundMsg.custom(tag, data).toJson();
        for (Player p : players) {
            if (p.isConnected()) pm.sendToPlayer(p.getId(), json);
        }
    }
}

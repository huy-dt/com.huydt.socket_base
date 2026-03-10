package com.huydt.loto_server_sdk.dispatcher;

import com.huydt.loto_server_sdk.model.LotoPage;
import com.huydt.loto_server_sdk.model.LotoPlayer;
import com.huydt.loto_server_sdk.model.LotoRoom;
import com.huydt.socket_base.server.core.PlayerManager;
import com.huydt.socket_base.server.core.RoomManager;
import com.huydt.socket_base.server.event.EventBus;
import com.huydt.socket_base.server.model.Player;
import com.huydt.socket_base.server.network.IClientHandler;
import com.huydt.socket_base.server.network.MessageDispatcher;
import com.huydt.socket_base.server.protocol.InboundMsg;
import com.huydt.socket_base.server.protocol.OutboundMsg;
import org.json.JSONObject;

/**
 * Routes CUSTOM messages for the Loto game.
 *
 * Supported tags:
 *   BUY_PAGE — player buys one page; deducts pricePerPage from money
 */
public class LotoDispatcher extends MessageDispatcher {

    public LotoDispatcher(PlayerManager pm, RoomManager rm,
                          EventBus bus, String adminToken) {
        super(pm, rm, bus, adminToken);
    }

    @Override
    protected void dispatchCustom(String connId, InboundMsg msg, IClientHandler handler) {
        Player base = playerManager.getByConnId(connId);
        if (base == null) return;

        LotoPlayer player = (LotoPlayer) base;
        String tag        = msg.getString("tag");
        JSONObject data   = msg.getObject("data");

        switch (tag != null ? tag : "") {
            case "BUY_PAGE":
                handleBuyPage(player, data, handler);
                break;
            default:
                handler.send(OutboundMsg.error("UNKNOWN_TAG",
                        "Unknown tag: " + tag).toJson());
        }
    }

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
    }
}

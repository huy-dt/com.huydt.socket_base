package com.huydt.loto_server_sdk.manager;

import com.huydt.loto_server_sdk.model.LotoRoom;
import com.huydt.socket_base.server.core.PlayerManager;
import com.huydt.socket_base.server.core.RoomManager;
import com.huydt.socket_base.server.core.ServerConfig;
import com.huydt.socket_base.server.event.EventBus;
import com.huydt.socket_base.server.model.Room;

/**
 * Wires {@link LotoRoom} into the SDK room factory.
 */
public class LotoRoomManager extends RoomManager {

    public LotoRoomManager(ServerConfig config, PlayerManager pm, EventBus bus) {
        super(config, pm, bus);
    }

    @Override
    protected Room newRoom(String id, String name, int maxPlayers) {
        return new LotoRoom(id, name, maxPlayers);
    }
}

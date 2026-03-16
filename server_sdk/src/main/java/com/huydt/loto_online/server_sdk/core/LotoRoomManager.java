package com.huydt.loto_online.server_sdk.core;

import com.huydt.loto_online.server_sdk.model.LotoRoom;
import com.huydt.socket_base.server.core.PlayerManager;
import com.huydt.socket_base.server.core.RoomManager;
import com.huydt.socket_base.server.core.ServerConfig;
import com.huydt.socket_base.server.event.EventBus;
import com.huydt.socket_base.server.model.Room;

/**
 * Overrides {@link RoomManager#newRoom} to produce {@link LotoRoom} instances.
 * Each room gets its own independent {@link GameFlow} scheduler.
 */
public class LotoRoomManager extends RoomManager {

    private final LotoConfig lotoConfig;

    public LotoRoomManager(ServerConfig baseConfig, PlayerManager pm,
                           EventBus bus, LotoConfig lotoConfig) {
        super(baseConfig, pm, bus);
        this.lotoConfig = lotoConfig;
    }

    @Override
    protected Room newRoom(String id, String name, int maxPlayers) {
        return new LotoRoom(id, name, maxPlayers,
                lotoConfig.pricePerPage,
                lotoConfig.drawIntervalMs,
                lotoConfig.autoResetDelayMs,
                lotoConfig.autoStartMs);
    }
}

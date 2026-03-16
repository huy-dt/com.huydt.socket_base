package com.huydt.loto_online.server_sdk.core;

import com.huydt.loto_online.server_sdk.model.LotoPlayer;
import com.huydt.socket_base.server.core.PlayerManager;
import com.huydt.socket_base.server.core.ServerConfig;
import com.huydt.socket_base.server.event.EventBus;
import com.huydt.socket_base.server.model.Player;

/**
 * Overrides {@link PlayerManager#createPlayer} to produce {@link LotoPlayer} instances.
 */
public class LotoPlayerManager extends PlayerManager {

    private final long initialBalance;

    public LotoPlayerManager(ServerConfig config, EventBus bus, long initialBalance) {
        super(config, bus);
        this.initialBalance = initialBalance;
    }

    @Override
    protected Player createPlayer(String name) {
        return new LotoPlayer(name, initialBalance);
    }
}

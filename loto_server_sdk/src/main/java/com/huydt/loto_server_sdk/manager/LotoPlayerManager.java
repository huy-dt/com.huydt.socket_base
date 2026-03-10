package com.huydt.loto_server_sdk.manager;

import com.huydt.loto_server_sdk.model.LotoPlayer;
import com.huydt.socket_base.server.core.PlayerManager;
import com.huydt.socket_base.server.core.ServerConfig;
import com.huydt.socket_base.server.event.EventBus;
import com.huydt.socket_base.server.model.Player;

/**
 * Wires {@link LotoPlayer} into the SDK player factory.
 */
public class LotoPlayerManager extends PlayerManager {

    public LotoPlayerManager(ServerConfig config, EventBus bus) {
        super(config, bus);
    }

    @Override
    protected Player createPlayer(String name) {
        return new LotoPlayer(name);
    }
}

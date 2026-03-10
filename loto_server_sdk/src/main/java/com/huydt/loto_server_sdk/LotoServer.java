package com.huydt.loto_server_sdk;

import com.huydt.loto_server_sdk.dispatcher.LotoDispatcher;
import com.huydt.loto_server_sdk.manager.LotoPlayerManager;
import com.huydt.loto_server_sdk.manager.LotoRoomManager;
import com.huydt.socket_base.server.core.PlayerManager;
import com.huydt.socket_base.server.core.RoomManager;
import com.huydt.socket_base.server.core.ServerConfig;
import com.huydt.socket_base.server.core.SocketBaseServer;
import com.huydt.socket_base.server.event.EventBus;
import com.huydt.socket_base.server.network.MessageDispatcher;

/**
 * Entry point for the Loto server.
 *
 * <pre>{@code
 * LotoServer server = new LotoServer(
 *     new ServerConfig.Builder()
 *         .wsPort(9001)
 *         .adminToken("my-secret")
 *         .reconnectTimeoutMs(30_000)
 *         .build()
 * );
 * new Thread(server::startSafe).start();
 * }</pre>
 */
public class LotoServer extends SocketBaseServer {

    public LotoServer(ServerConfig config) {
        super(config);
    }

    @Override
    protected PlayerManager createPlayerManager(ServerConfig config, EventBus bus) {
        return new LotoPlayerManager(config, bus);
    }

    @Override
    protected RoomManager createRoomManager(ServerConfig config, PlayerManager pm, EventBus bus) {
        return new LotoRoomManager(config, pm, bus);
    }

    @Override
    protected MessageDispatcher createDispatcher(PlayerManager pm, RoomManager rm,
                                                  EventBus bus, String adminToken) {
        return new LotoDispatcher(pm, rm, bus, adminToken);
    }
}

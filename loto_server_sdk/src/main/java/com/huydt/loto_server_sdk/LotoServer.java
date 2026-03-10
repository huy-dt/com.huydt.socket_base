package com.huydt.loto_server_sdk;

import com.huydt.loto_server_sdk.dispatcher.LotoDispatcher;
import com.huydt.loto_server_sdk.manager.LotoPlayerManager;
import com.huydt.loto_server_sdk.manager.LotoRoomManager;
import com.huydt.socket_base.server.core.PlayerManager;
import com.huydt.socket_base.server.core.RoomManager;
import com.huydt.socket_base.server.core.ServerConfig;
import com.huydt.socket_base.server.core.SocketBaseServer;
import com.huydt.socket_base.server.event.EventBus;
import com.huydt.socket_base.server.event.EventType;
import com.huydt.socket_base.server.network.MessageDispatcher;

public class LotoServer extends SocketBaseServer {

    private LotoDispatcher lotoDispatcher;

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
        lotoDispatcher = new LotoDispatcher(pm, rm, bus, adminToken);
        return lotoDispatcher;
    }

    /**
     * Called by startSafe() after all components are ready.
     * Inject AdminService into dispatcher here so engines have access to it.
     */
    @Override
    protected void onServerStarted() {
        if (lotoDispatcher != null) {
            lotoDispatcher.setAdminService(getAdmin());
        }
    }
}

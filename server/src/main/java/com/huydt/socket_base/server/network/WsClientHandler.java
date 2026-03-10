package com.huydt.socket_base.server.network;

import org.java_websocket.WebSocket;

/**
 * Wraps a {@link WebSocket} connection to implement {@link IClientHandler},
 * giving it the same interface as TCP connections.
 */
public class WsClientHandler implements IClientHandler {

    private final String    connectionId;
    private final WebSocket ws;

    public WsClientHandler(String connectionId, WebSocket ws) {
        this.connectionId = connectionId;
        this.ws           = ws;
    }

    @Override
    public String getConnectionId() { return connectionId; }

    @Override
    public String getRemoteIp() {
        try {
            return ws.getRemoteSocketAddress().getAddress().getHostAddress();
        } catch (Exception e) {
            return "unknown";
        }
    }

    @Override
    public boolean isConnected() {
        return ws != null && ws.isOpen();
    }

    @Override
    public synchronized void send(String json) {
        if (isConnected()) {
            try { ws.send(json); } catch (Exception e) {
                System.err.println("[WsClientHandler] Send error: " + e.getMessage());
            }
        }
    }

    @Override
    public void close() {
        try { if (isConnected()) ws.close(); } catch (Exception ignored) {}
    }
}

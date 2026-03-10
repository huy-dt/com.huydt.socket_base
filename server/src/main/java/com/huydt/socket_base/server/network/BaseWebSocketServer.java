package com.huydt.socket_base.server.network;

import com.huydt.socket_base.server.protocol.InboundMsg;
import com.huydt.socket_base.server.protocol.OutboundMsg;
import org.java_websocket.WebSocket;
import org.java_websocket.handshake.ClientHandshake;
import org.java_websocket.server.WebSocketServer;

import java.net.InetSocketAddress;
import java.util.Map;
import java.util.Objects;
import java.util.UUID;
import java.util.concurrent.ConcurrentHashMap;

/**
 * WebSocket transport layer.
 * Each WS connection gets a {@link WsClientHandler} wrapper so it satisfies
 * the same {@link IClientHandler} contract used by TCP.
 */
public class BaseWebSocketServer extends WebSocketServer {

    private final MessageDispatcher               dispatcher;
    private final Map<WebSocket, WsClientHandler> handlers = new ConcurrentHashMap<>();

    public BaseWebSocketServer(int port, MessageDispatcher dispatcher) {
        super(new InetSocketAddress(port));
        this.dispatcher = Objects.requireNonNull(dispatcher,
                "dispatcher must not be null — check SocketBaseServer constructor ordering");
        setReuseAddr(true);
    }

    // ── WebSocketServer callbacks ─────────────────────────────────────

    @Override
    public void onOpen(WebSocket conn, ClientHandshake handshake) {
        String connId = UUID.randomUUID().toString().replace("-", "").substring(0, 8);
        handlers.put(conn, new WsClientHandler(connId, conn));
        System.out.println("[WS] Connected: " + connId + " from " + conn.getRemoteSocketAddress());
    }

    @Override
    public void onMessage(WebSocket conn, String message) {
        WsClientHandler h = handlers.get(conn);
        if (h == null) return;

        message = message.trim();
        if (message.isEmpty()) return;

        InboundMsg msg = InboundMsg.parse(message);
        if (msg == null) {
            h.send(OutboundMsg.error("PARSE_ERROR", "Invalid JSON or unknown type").toJson());
            return;
        }
        dispatcher.dispatch(h.getConnectionId(), msg, h);
    }

    @Override
    public void onClose(WebSocket conn, int code, String reason, boolean remote) {
        WsClientHandler h = handlers.remove(conn);
        if (h != null) {
            System.out.println("[WS] Disconnected: " + h.getConnectionId() + " reason=" + reason);
            dispatcher.onDisconnected(h.getConnectionId());
        }
    }

    @Override
    public void onError(WebSocket conn, Exception ex) {
        System.err.println("[WS] Error: " + ex.getMessage());
        if (conn != null) {
            WsClientHandler h = handlers.remove(conn);
            if (h != null) dispatcher.onDisconnected(h.getConnectionId());
        }
    }

    @Override
    public void onStart() {
        System.out.println("[WS] Server started on port " + getPort());
    }

    /** Safe start — swallows exceptions so this can be passed to a {@link Thread}. */
    public void startSafe() {
        try {
            start();
        } catch (Exception e) {
            System.err.println("[WS] Failed to start: " + e.getMessage());
        }
    }
}

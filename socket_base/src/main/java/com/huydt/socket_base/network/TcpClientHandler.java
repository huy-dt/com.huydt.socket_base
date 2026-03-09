package com.huydt.socket_base.network;

import com.huydt.socket_base.protocol.InboundMsg;
import com.huydt.socket_base.protocol.OutboundMsg;

import java.io.BufferedReader;
import java.io.InputStreamReader;
import java.io.PrintWriter;
import java.net.InetAddress;
import java.net.Socket;

/**
 * Manages a single TCP client connection.
 * Reads newline-delimited JSON messages and forwards them to the dispatcher.
 */
public class TcpClientHandler implements Runnable, IClientHandler {

    private final String            connectionId;
    private final Socket            socket;
    private final MessageDispatcher dispatcher;
    private       PrintWriter       writer;
    private volatile boolean        running = true;

    public TcpClientHandler(String connectionId, Socket socket, MessageDispatcher dispatcher) {
        this.connectionId = connectionId;
        this.socket       = socket;
        this.dispatcher   = dispatcher;
    }

    @Override
    public void run() {
        try (BufferedReader reader = new BufferedReader(
                new InputStreamReader(socket.getInputStream(), "UTF-8"))) {

            writer = new PrintWriter(socket.getOutputStream(), true);

            String line;
            while (running && (line = reader.readLine()) != null) {
                line = line.trim();
                if (line.isEmpty()) continue;

                InboundMsg msg = InboundMsg.parse(line);
                if (msg == null) {
                    send(OutboundMsg.error("PARSE_ERROR", "Invalid JSON or unknown type").toJson());
                    continue;
                }
                dispatcher.dispatch(connectionId, msg, this);
            }
        } catch (Exception e) {
            // socket dropped — normal exit path
        } finally {
            dispatcher.onDisconnected(connectionId);
            close();
        }
    }

    // ── IClientHandler ────────────────────────────────────────────────

    @Override
    public String getConnectionId() { return connectionId; }

    @Override
    public String getRemoteIp() {
        InetAddress addr = socket.getInetAddress();
        return addr != null ? addr.getHostAddress() : "unknown";
    }

    @Override
    public boolean isConnected() { return !socket.isClosed() && socket.isConnected(); }

    @Override
    public synchronized void send(String json) {
        if (writer != null && !socket.isClosed()) {
            writer.println(json);
        }
    }

    @Override
    public void close() {
        running = false;
        try { socket.close(); } catch (Exception ignored) {}
    }
}

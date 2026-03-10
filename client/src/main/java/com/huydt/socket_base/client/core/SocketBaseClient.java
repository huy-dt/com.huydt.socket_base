package com.huydt.socket_base.client.core;

import com.huydt.socket_base.client.event.ClientEvent;
import com.huydt.socket_base.client.event.ClientEventBus;
import com.huydt.socket_base.client.event.ClientEventType;
import com.huydt.socket_base.client.protocol.InboundMsg;
import com.huydt.socket_base.client.protocol.OutboundMsg;
import org.java_websocket.client.WebSocketClient;
import org.java_websocket.handshake.ServerHandshake;
import org.json.JSONObject;

import javax.net.ssl.SSLContext;
import javax.net.ssl.SSLSocket;
import javax.net.ssl.TrustManager;
import javax.net.ssl.X509TrustManager;
import java.io.*;
import java.net.*;
import java.nio.charset.StandardCharsets;
import java.security.SecureRandom;
import java.security.cert.X509Certificate;
import java.util.Objects;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.TimeoutException;
import java.util.concurrent.atomic.AtomicBoolean;

/**
 * Multi-transport client for {@code socket_base} servers.
 *
 * <h3>Supported transports</h3>
 * <ul>
 *   <li><b>WS / WSS</b>  — WebSocket (default). URL may omit port.</li>
 *   <li><b>TCP / TCP_SSL</b> — Raw newline-delimited JSON over TCP.</li>
 *   <li><b>UDP / UDP_SSL</b> — Newline-terminated JSON datagrams over UDP.</li>
 * </ul>
 *
 * <h3>Minimal usage — WebSocket (default)</h3>
 * <pre>
 * SocketBaseClient client = new SocketBaseClient.Builder()
 *     .host("localhost").port(9001).name("Alice")
 *     .build();
 * client.on(ClientEventType.WELCOME, e -> System.out.println("Joined!"));
 * client.connect();
 * </pre>
 *
 * <h3>TCP + SSL</h3>
 * <pre>
 * SocketBaseClient client = new SocketBaseClient.Builder()
 *     .host("game.example.com").port(9002)
 *     .protocol(ClientConfig.Protocol.TCP_SSL)
 *     .name("Alice")
 *     .build();
 * client.connect();
 * </pre>
 *
 * <h3>UDP</h3>
 * <pre>
 * SocketBaseClient client = new SocketBaseClient.Builder()
 *     .host("game.example.com").port(9003)
 *     .protocol(ClientConfig.Protocol.UDP)
 *     .name("Alice")
 *     .build();
 * client.connect();
 * </pre>
 *
 * <h3>Full URL (WS — port optional)</h3>
 * <pre>
 * SocketBaseClient client = new SocketBaseClient.Builder()
 *     .url("wss://game.example.com/ws")   // port 443 inferred
 *     .name("Alice")
 *     .build();
 * client.connect();
 * </pre>
 */
public class SocketBaseClient {

    // ── State ─────────────────────────────────────────────────────────

    private final ClientConfig   config;
    private final ClientEventBus bus;
    private final ClientSession  session;

    private final String playerName;
    private final String preferredRoomId;
    private final String adminToken;
    private final String reconnectToken;

    // WebSocket transport
    private volatile WebSocketClient ws;

    // TCP transport
    private volatile Socket     tcpSocket;
    private volatile PrintWriter tcpWriter;

    // UDP transport
    private volatile DatagramSocket udpSocket;
    private volatile InetAddress    udpAddress;

    private final AtomicBoolean connected = new AtomicBoolean(false);

    // ── Constructor ───────────────────────────────────────────────────

    protected SocketBaseClient(Builder b) {
        this.config          = Objects.requireNonNull(b.config, "config must not be null");
        this.bus             = new ClientEventBus();
        this.session         = new ClientSession();
        this.playerName      = b.name;
        this.preferredRoomId = b.roomId;
        this.adminToken      = b.adminToken;
        this.reconnectToken  = b.reconnectToken;
    }

    // ── Lifecycle ─────────────────────────────────────────────────────

    /** Connect asynchronously. Fires {@link ClientEventType#CONNECTED} on success. */
    public void connect() {
        switch (config.protocol) {
            case WS:
            case WSS:
                doConnectWs();
                break;
            case TCP:
            case TCP_SSL:
                doConnectTcp();
                break;
            case UDP:
            case UDP_SSL:
                doConnectUdp();
                break;
        }
    }

    /**
     * Connect synchronously — blocks until connected or timeout.
     *
     * @throws InterruptedException if interrupted
     * @throws TimeoutException     if connection exceeds {@link ClientConfig#connectTimeoutMs}
     */
    public void connectBlocking() throws InterruptedException, TimeoutException {
        CountDownLatch latch = new CountDownLatch(1);
        on(ClientEventType.CONNECTED,    e -> latch.countDown());
        on(ClientEventType.DISCONNECTED, e -> latch.countDown());
        connect();
        int timeout = config.connectTimeoutMs > 0 ? config.connectTimeoutMs : 10_000;
        if (!latch.await(timeout, TimeUnit.MILLISECONDS))
            throw new TimeoutException("Connection timed out after " + timeout + "ms");
    }

    /** Disconnect gracefully (all transports). */
    public void disconnect() {
        connected.set(false);
        if (ws != null)        { try { ws.close();        } catch (Exception ignored) {} }
        if (tcpSocket != null) { try { tcpSocket.close(); } catch (Exception ignored) {} }
        if (udpSocket != null) { udpSocket.close(); }
    }

    // ── Send ──────────────────────────────────────────────────────────

    /**
     * Send a raw JSON string to the server.
     * @return {@code true} if dispatched, {@code false} if not connected
     */
    public boolean send(String json) {
        if (!connected.get()) {
            System.err.println("[Client] Cannot send — not connected");
            return false;
        }
        switch (config.protocol) {
            case WS:
            case WSS:
                if (ws == null) return false;
                ws.send(json);
                return true;

            case TCP:
            case TCP_SSL:
                if (tcpWriter == null) return false;
                tcpWriter.println(json);          // newline-delimited framing
                return !tcpWriter.checkError();

            case UDP:
            case UDP_SSL:
                if (udpSocket == null || udpAddress == null) return false;
                return sendUdpPacket(json);

            default:
                return false;
        }
    }

    // ── Player API ────────────────────────────────────────────────────

    public boolean joinRoom(String roomId)                    { return joinRoom(roomId, null); }
    public boolean joinRoom(String roomId, String password)   { return send(OutboundMsg.joinRoom(roomId, password)); }
    public boolean leaveRoom()                                { return send(OutboundMsg.leaveRoom()); }
    public boolean custom(String tag, JSONObject data)        { return send(OutboundMsg.custom(tag, data)); }
    public boolean custom(String tag)                         { return custom(tag, null); }

    // ── Admin API ─────────────────────────────────────────────────────

    public boolean adminAuth(String token)                              { return send(OutboundMsg.adminAuth(token)); }
    public boolean kick(String playerId, String reason)                 { return send(OutboundMsg.kick(playerId, reason)); }
    public boolean ban(String playerId, String reason)                  { return send(OutboundMsg.ban(playerId, reason)); }
    public boolean unban(String name)                                   { return send(OutboundMsg.unban(name)); }
    public boolean banIp(String ip)                                     { return send(OutboundMsg.banIp(ip)); }
    public boolean unbanIp(String ip)                                   { return send(OutboundMsg.unbanIp(ip)); }
    public boolean getBanList()                                         { return send(OutboundMsg.getBanList()); }
    public boolean createRoom(String name)                              { return send(OutboundMsg.createRoom(name)); }
    public boolean createRoom(String name, int max, String pwd)         { return send(OutboundMsg.createRoom(name, max, pwd)); }
    public boolean closeRoom(String roomId)                             { return send(OutboundMsg.closeRoom(roomId)); }
    public boolean listRooms()                                          { return send(OutboundMsg.listRooms()); }
    public boolean getRoom(String roomId)                               { return send(OutboundMsg.getRoom(roomId)); }
    public boolean setRoomState(String roomId, String state)            { return send(OutboundMsg.setRoomState(roomId, state)); }
    public boolean adminBroadcast(String roomId, String tag, JSONObject d) { return send(OutboundMsg.adminBroadcast(roomId, tag, d)); }
    public boolean adminSend(String playerId, String tag, JSONObject d)    { return send(OutboundMsg.adminSend(playerId, tag, d)); }
    public boolean getStats()                                           { return send(OutboundMsg.getStats()); }

    // ── Events ────────────────────────────────────────────────────────

    public SocketBaseClient on(ClientEventType type,
                               com.huydt.socket_base.client.event.ClientEventListener listener) {
        bus.on(type, listener);
        return this;
    }

    public SocketBaseClient onError(java.util.function.BiConsumer<ClientEvent, Throwable> handler) {
        bus.onError(handler);
        return this;
    }

    // ── Accessors ─────────────────────────────────────────────────────

    public ClientSession getSession()  { return session; }
    public ClientConfig  getConfig()   { return config; }
    public boolean       isConnected() { return connected.get(); }

    // ── Internal: WebSocket transport ─────────────────────────────────

    private void doConnectWs() {
        try {
            URI uri = new URI(config.wsUri());
            ws = new WebSocketClient(uri) {

                @Override public void onOpen(ServerHandshake h) {
                    connected.set(true);
                    System.out.println("[Client] WS connected → " + config.wsUri());
                    bus.emit(ClientEvent.of(ClientEventType.CONNECTED, null));
                    sendJoin();
                }

                @Override public void onMessage(String message) { handleMessage(message); }

                @Override public void onClose(int code, String reason, boolean remote) {
                    connected.set(false);
                    System.out.printf("[Client] WS disconnected (code=%d, reason=%s, remote=%b)%n",
                            code, reason, remote);
                    bus.emit(new ClientEvent.Builder(ClientEventType.DISCONNECTED)
                            .message(reason).build());
                }

                @Override public void onError(Exception ex) {
                    System.err.println("[Client] WS error: " + ex.getMessage());
                    bus.emit(ClientEvent.error(ex.getMessage(), ex));
                }
            };

            if (config.connectTimeoutMs > 0)
                ws.setConnectionLostTimeout(config.connectTimeoutMs / 1000);
            ws.connect();

        } catch (Exception e) {
            bus.emit(ClientEvent.error("Failed to create WebSocket: " + e.getMessage(), e));
        }
    }

    // ── Internal: TCP transport ───────────────────────────────────────

    private void doConnectTcp() {
        Thread t = new Thread(() -> {
            try {
                String host = config.host;
                int    port = config.effectivePort();

                Socket sock;
                if (config.protocol == ClientConfig.Protocol.TCP_SSL) {
                    SSLContext ctx = buildTrustAllSsl();
                    sock = ctx.getSocketFactory().createSocket(host, port);
                } else {
                    sock = new Socket();
                    int timeout = config.connectTimeoutMs > 0 ? config.connectTimeoutMs : 10_000;
                    sock.connect(new InetSocketAddress(host, port), timeout);
                }

                tcpSocket = sock;
                tcpWriter = new PrintWriter(
                        new BufferedWriter(new OutputStreamWriter(sock.getOutputStream(), StandardCharsets.UTF_8)),
                        true);

                connected.set(true);
                System.out.println("[Client] TCP connected → " + config.tcpAddress());
                bus.emit(ClientEvent.of(ClientEventType.CONNECTED, null));
                sendJoin();

                // Read loop — newline-delimited JSON
                BufferedReader reader = new BufferedReader(
                        new InputStreamReader(sock.getInputStream(), StandardCharsets.UTF_8));
                String line;
                while ((line = reader.readLine()) != null) {
                    if (!line.isBlank()) handleMessage(line);
                }

            } catch (Exception e) {
                if (connected.get()) {
                    System.err.println("[Client] TCP error: " + e.getMessage());
                    bus.emit(ClientEvent.error(e.getMessage(), e));
                }
            } finally {
                connected.set(false);
                bus.emit(new ClientEvent.Builder(ClientEventType.DISCONNECTED)
                        .message("TCP connection closed").build());
            }
        }, "tcp-reader");
        t.setDaemon(true);
        t.start();
    }

    // ── Internal: UDP transport ───────────────────────────────────────

    private void doConnectUdp() {
        Thread t = new Thread(() -> {
            try {
                udpAddress = InetAddress.getByName(config.host);
                int port   = config.effectivePort();

                udpSocket = new DatagramSocket();
                if (config.connectTimeoutMs > 0)
                    udpSocket.setSoTimeout(0); // non-blocking receive; keep alive

                connected.set(true);
                System.out.println("[Client] UDP ready → " + config.tcpAddress());
                bus.emit(ClientEvent.of(ClientEventType.CONNECTED, null));
                sendJoin();

                // Receive loop
                byte[] buf = new byte[65_507];
                while (connected.get() && !udpSocket.isClosed()) {
                    DatagramPacket pkt = new DatagramPacket(buf, buf.length);
                    udpSocket.receive(pkt);
                    String raw = new String(pkt.getData(), 0, pkt.getLength(), StandardCharsets.UTF_8).trim();
                    if (!raw.isBlank()) handleMessage(raw);
                }

            } catch (Exception e) {
                if (connected.get()) {
                    System.err.println("[Client] UDP error: " + e.getMessage());
                    bus.emit(ClientEvent.error(e.getMessage(), e));
                }
            } finally {
                connected.set(false);
                bus.emit(new ClientEvent.Builder(ClientEventType.DISCONNECTED)
                        .message("UDP connection closed").build());
            }
        }, "udp-reader");
        t.setDaemon(true);
        t.start();
    }

    private boolean sendUdpPacket(String json) {
        try {
            byte[] data = (json + "\n").getBytes(StandardCharsets.UTF_8);
            DatagramPacket pkt = new DatagramPacket(data, data.length, udpAddress, config.effectivePort());
            udpSocket.send(pkt);
            return true;
        } catch (IOException e) {
            System.err.println("[Client] UDP send error: " + e.getMessage());
            return false;
        }
    }

    // ── Internal: post-connect JOIN sequence ──────────────────────────

    private void sendJoin() {
        if (adminToken != null) send(OutboundMsg.adminAuth(adminToken));
        String token = reconnectToken != null ? reconnectToken : session.getToken();
        send(OutboundMsg.join(playerName, token, preferredRoomId));
    }

    // ── Internal: SSL helper (trust-all for dev/testing) ──────────────

    private static SSLContext buildTrustAllSsl() throws Exception {
        TrustManager[] tm = { new X509TrustManager() {
            public void checkClientTrusted(X509Certificate[] c, String a) {}
            public void checkServerTrusted(X509Certificate[] c, String a) {}
            public X509Certificate[] getAcceptedIssuers() { return new X509Certificate[0]; }
        }};
        SSLContext ctx = SSLContext.getInstance("TLS");
        ctx.init(null, tm, new SecureRandom());
        return ctx;
    }

    // ── Message dispatch ──────────────────────────────────────────────

    private void handleMessage(String raw) {
        InboundMsg msg = InboundMsg.parse(raw);
        if (msg == null) {
            System.err.println("[Client] Unparseable message: " + raw);
            return;
        }

        JSONObject payload = msg.getPayload();

        switch (msg.getType()) {

            case WELCOME:
                session.update(payload);
                bus.emit(ClientEvent.of(ClientEventType.WELCOME, payload));
                break;

            case RECONNECTED:
                session.update(payload);
                bus.emit(ClientEvent.of(ClientEventType.RECONNECTED, payload));
                break;

            case APP_SNAPSHOT:
                bus.emit(ClientEvent.of(ClientEventType.APP_SNAPSHOT, payload));
                break;

            case ROOM_SNAPSHOT: {
                JSONObject roomSnap = payload.optJSONObject("room");
                if (roomSnap != null) session.onRoomSnapshot(roomSnap);
                bus.emit(ClientEvent.of(ClientEventType.ROOM_SNAPSHOT, payload));
                break;
            }

            case PLAYER_JOINED: {
                JSONObject p = payload.optJSONObject("player");
                if (p != null) session.onPlayerJoined(p);
                bus.emit(ClientEvent.of(ClientEventType.PLAYER_JOINED, payload));
                break;
            }

            case PLAYER_LEFT:
                session.onPlayerLeft(
                        payload.optString("playerId"),
                        payload.optBoolean("permanent", false));
                bus.emit(ClientEvent.of(ClientEventType.PLAYER_LEFT, payload));
                break;

            case PLAYER_RECONNECTED: {
                JSONObject p = payload.optJSONObject("player");
                if (p != null) session.onPlayerReconnected(p);
                bus.emit(ClientEvent.of(ClientEventType.PLAYER_RECONNECTED, payload));
                break;
            }

            case PLAYER_UPDATE: {
                JSONObject p = payload.optJSONObject("player");
                if (p != null) session.onPlayerUpdate(p);
                bus.emit(ClientEvent.of(ClientEventType.PLAYER_UPDATE, payload));
                break;
            }

            case ROOM_UPDATE: {
                JSONObject r = payload.optJSONObject("room");
                if (r != null) session.onRoomUpdate(r);
                bus.emit(ClientEvent.of(ClientEventType.ROOM_UPDATE, payload));
                break;
            }

            case ROOM_INFO:
                bus.emit(ClientEvent.of(ClientEventType.ROOM_INFO, payload));
                break;

            case ROOM_LIST:
                bus.emit(ClientEvent.of(ClientEventType.ROOM_LIST, payload));
                break;

            case ROOM_STATE_CHANGED:
                if (payload.optString("roomId", "").equals(session.getRoomId()))
                    session.onRoomUpdate(payload);
                bus.emit(ClientEvent.of(ClientEventType.ROOM_STATE_CHANGED, payload));
                break;

            case KICKED:
                bus.emit(ClientEvent.of(ClientEventType.KICKED, payload));
                break;

            case BANNED:
                bus.emit(ClientEvent.of(ClientEventType.BANNED, payload));
                break;

            case ADMIN_AUTH_OK:
                session.setAdmin(true);
                bus.emit(ClientEvent.of(ClientEventType.ADMIN_AUTH_OK, payload));
                break;

            case BAN_LIST:
                bus.emit(ClientEvent.of(ClientEventType.BAN_LIST, payload));
                break;

            case STATS:
                bus.emit(ClientEvent.of(ClientEventType.STATS, payload));
                break;

            case CUSTOM_MSG:
                dispatchCustom(msg, payload);
                break;

            case ERROR: {
                String code   = payload.optString("code",   "ERROR");
                String detail = payload.optString("detail", "Unknown error");
                System.err.println("[Client] Server error: " + code + " — " + detail);
                bus.emit(new ClientEvent.Builder(ClientEventType.ERROR)
                        .payload(payload).message(code + ": " + detail).build());
                break;
            }

            default:
                System.out.println("[Client] Unhandled type: " + msg.getType());
        }
    }

    /**
     * Override to handle {@code CUSTOM_MSG} with your own tag routing.
     *
     * <pre>
     * &#64;Override
     * protected void dispatchCustom(InboundMsg msg, JSONObject payload) {
     *     String tag = payload.optString("tag");
     *     if ("ROUND_START".equals(tag)) { ... }
     * }
     * </pre>
     */
    protected void dispatchCustom(InboundMsg msg, JSONObject payload) {
        bus.emit(ClientEvent.of(ClientEventType.CUSTOM_MSG, payload));
    }

    // ── Builder ───────────────────────────────────────────────────────

    public static class Builder {
        private ClientConfig config         = new ClientConfig.Builder().build();
        private String       name           = "Player";
        private String       roomId         = null;
        private String       adminToken     = null;
        private String       reconnectToken = null;

        /** Full config object. */
        public Builder config(ClientConfig c) { this.config = c; return this; }

        // ── Shorthand setters ─────────────────────────────────────────

        public Builder host(String host)         { config = copy(b -> b.host(host));       return this; }
        public Builder port(int port)            { config = copy(b -> b.port(port));       return this; }
        public Builder useSsl(boolean ssl)       { config = copy(b -> b.useSsl(ssl));      return this; }
        public Builder protocol(ClientConfig.Protocol p) { config = copy(b -> b.protocol(p)); return this; }

        /**
         * Set connection from a full URL.
         * <pre>
         * .url("wss://game.example.com/ws")
         * .url("tcp+ssl://game.example.com:9002")
         * </pre>
         */
        public Builder url(String url)           { config = copy(b -> b.url(url));         return this; }

        /** Player display name sent with JOIN. */
        public Builder name(String name)         { this.name = name; return this; }

        /** Auto-join this room after WELCOME. */
        public Builder roomId(String roomId)     { this.roomId = roomId; return this; }

        /** Token from a previous session for reconnect. */
        public Builder reconnectToken(String t)  { this.reconnectToken = t; return this; }

        /** Admin token — auto-sends ADMIN_AUTH before JOIN. */
        public Builder adminToken(String t)      { this.adminToken = t; return this; }

        public SocketBaseClient build() { return new SocketBaseClient(this); }

        private ClientConfig copy(java.util.function.Consumer<ClientConfig.Builder> fn) {
            ClientConfig.Builder b = new ClientConfig.Builder()
                    .protocol(config.protocol)
                    .host(config.host)
                    .port(config.port > 0 ? config.port : -1)
                    .connectTimeoutMs(config.connectTimeoutMs)
                    .reconnectTimeoutMs(config.reconnectTimeoutMs);
            if (config.path != null) b.path(config.path);
            fn.accept(b);
            return b.build();
        }
    }
}

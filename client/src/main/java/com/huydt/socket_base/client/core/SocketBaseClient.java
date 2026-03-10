package com.huydt.socket_base.client.core;

import com.huydt.socket_base.client.event.ClientEvent;
import com.huydt.socket_base.client.event.ClientEventBus;
import com.huydt.socket_base.client.event.ClientEventType;
import com.huydt.socket_base.client.protocol.InboundMsg;
import com.huydt.socket_base.client.protocol.OutboundMsg;
import org.java_websocket.client.WebSocketClient;
import org.java_websocket.handshake.ServerHandshake;
import org.json.JSONObject;

import java.net.URI;
import java.util.Objects;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.TimeoutException;
import java.util.concurrent.atomic.AtomicBoolean;

/**
 * WebSocket client for {@code socket_base} servers.
 *
 * <h3>Minimal usage</h3>
 * <pre>
 * SocketBaseClient client = new SocketBaseClient.Builder()
 *     .host("localhost").port(9001).name("Alice")
 *     .build();
 *
 * client.on(ClientEventType.WELCOME, e ->
 *     System.out.println("Joined! token=" + client.getSession().getToken()));
 *
 * client.connect();
 * </pre>
 *
 * <h3>Reconnect</h3>
 * Pass a saved token on next launch to restore the previous session:
 * <pre>
 * SocketBaseClient client = new SocketBaseClient.Builder()
 *     .reconnectToken(savedToken)
 *     .build();
 * client.connect();
 * </pre>
 *
 * <h3>Snapshot types</h3>
 * <ul>
 *   <li>In the <b>lobby</b>: server sends {@code APP_SNAPSHOT} — full player list + room list.</li>
 *   <li>Inside a <b>room</b>: server sends {@code ROOM_SNAPSHOT} — current room state only.</li>
 * </ul>
 *
 * <h3>Extension point</h3>
 * Override {@link #dispatchCustom} to handle {@code CUSTOM_MSG} with your own tag routing.
 */
public class SocketBaseClient {

    // ── State ─────────────────────────────────────────────────────────

    private final ClientConfig   config;
    private final ClientEventBus bus;
    private final ClientSession  session;

    private final String playerName;
    private final String preferredRoomId;
    private final String adminToken;       // auto-sends ADMIN_AUTH on connect if set
    private final String reconnectToken;   // token from a previous session

    private volatile WebSocketClient ws;
    private final    AtomicBoolean   connected = new AtomicBoolean(false);

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

    /**
     * Connect to the server asynchronously.
     * Fires {@link ClientEventType#CONNECTED} on success, then sends JOIN automatically.
     */
    public void connect() {
        doConnect();
    }

    /**
     * Connect synchronously — blocks until connected or timeout.
     *
     * @throws InterruptedException if the calling thread is interrupted
     * @throws TimeoutException     if the connection takes longer than {@link ClientConfig#connectTimeoutMs}
     */
    public void connectBlocking() throws InterruptedException, TimeoutException {
        CountDownLatch latch = new CountDownLatch(1);
        on(ClientEventType.CONNECTED,    e -> latch.countDown());
        on(ClientEventType.DISCONNECTED, e -> latch.countDown()); // fail-fast on immediate error
        connect();
        int timeout = config.connectTimeoutMs > 0 ? config.connectTimeoutMs : 10_000;
        if (!latch.await(timeout, TimeUnit.MILLISECONDS)) {
            throw new TimeoutException("Connection timed out after " + timeout + "ms");
        }
    }

    /** Disconnect gracefully. */
    public void disconnect() {
        if (ws != null) ws.close();
    }

    // ── Send raw JSON ─────────────────────────────────────────────────

    /**
     * Send a raw JSON string to the server.
     *
     * @return {@code true} if sent, {@code false} if not connected
     */
    public boolean send(String json) {
        if (!connected.get() || ws == null) {
            System.err.println("[Client] Cannot send — not connected");
            return false;
        }
        ws.send(json);
        return true;
    }

    // ── Player API ────────────────────────────────────────────────────

    /** Join a room (no password). */
    public boolean joinRoom(String roomId) {
        return joinRoom(roomId, null);
    }

    /** Join a room with an optional password. */
    public boolean joinRoom(String roomId, String password) {
        return send(OutboundMsg.joinRoom(roomId, password));
    }

    /**
     * Leave the current room and return to the lobby.
     * Session state is updated when the server sends {@code PLAYER_LEFT(permanent=true)}.
     */
    public boolean leaveRoom() {
        return send(OutboundMsg.leaveRoom());
    }

    /** Send a custom application event with a data payload. */
    public boolean custom(String tag, JSONObject data) {
        return send(OutboundMsg.custom(tag, data));
    }

    /** Send a custom application event with no data. */
    public boolean custom(String tag) {
        return custom(tag, null);
    }

    // ── Admin API ─────────────────────────────────────────────────────

    public boolean adminAuth(String token)                              { return send(OutboundMsg.adminAuth(token)); }
    public boolean kick(String playerId, String reason)                 { return send(OutboundMsg.kick(playerId, reason)); }
    public boolean ban(String playerId, String reason)                  { return send(OutboundMsg.ban(playerId, reason)); }
    public boolean unban(String name)                                   { return send(OutboundMsg.unban(name)); }
    public boolean banIp(String ip)                                     { return send(OutboundMsg.banIp(ip)); }
    public boolean unbanIp(String ip)                                   { return send(OutboundMsg.unbanIp(ip)); }
    public boolean getBanList()                                         { return send(OutboundMsg.getBanList()); }
    public boolean createRoom(String name)                              { return send(OutboundMsg.createRoom(name)); }
    public boolean createRoom(String name, int maxPlayers, String pwd)  { return send(OutboundMsg.createRoom(name, maxPlayers, pwd)); }
    public boolean closeRoom(String roomId)                             { return send(OutboundMsg.closeRoom(roomId)); }
    public boolean listRooms()                                          { return send(OutboundMsg.listRooms()); }
    public boolean getRoom(String roomId)                               { return send(OutboundMsg.getRoom(roomId)); }
    public boolean setRoomState(String roomId, String state)            { return send(OutboundMsg.setRoomState(roomId, state)); }
    public boolean adminBroadcast(String roomId, String tag, JSONObject data) { return send(OutboundMsg.adminBroadcast(roomId, tag, data)); }
    public boolean adminSend(String playerId, String tag, JSONObject data)    { return send(OutboundMsg.adminSend(playerId, tag, data)); }
    public boolean getStats()                                           { return send(OutboundMsg.getStats()); }

    // ── Event registration ────────────────────────────────────────────

    /** Register an event listener. Returns {@code this} for chaining. */
    public SocketBaseClient on(ClientEventType type,
                               com.huydt.socket_base.client.event.ClientEventListener listener) {
        bus.on(type, listener);
        return this;
    }

    /** Register a global error handler for listener exceptions. */
    public SocketBaseClient onError(java.util.function.BiConsumer<ClientEvent, Throwable> handler) {
        bus.onError(handler);
        return this;
    }

    // ── Accessors ─────────────────────────────────────────────────────

    public ClientSession getSession()  { return session; }
    public ClientConfig  getConfig()   { return config; }
    public boolean       isConnected() { return connected.get(); }

    // ── Internal: WebSocket ───────────────────────────────────────────

    private void doConnect() {
        try {
            URI uri = new URI(config.wsUri());
            ws = new WebSocketClient(uri) {

                @Override
                public void onOpen(ServerHandshake handshake) {
                    connected.set(true);
                    System.out.println("[Client] Connected → " + config.wsUri());
                    bus.emit(ClientEvent.of(ClientEventType.CONNECTED, null));

                    // Send ADMIN_AUTH first if provided
                    if (adminToken != null) {
                        send(OutboundMsg.adminAuth(adminToken));
                    }

                    // Token priority: CLI --token > session token (from previous WELCOME) > null (fresh join)
                    String token = reconnectToken != null ? reconnectToken : session.getToken();
                    send(OutboundMsg.join(playerName, token, preferredRoomId));
                }

                @Override
                public void onMessage(String message) {
                    handleMessage(message);
                }

                @Override
                public void onClose(int code, String reason, boolean remote) {
                    connected.set(false);
                    System.out.printf("[Client] Disconnected (code=%d, reason=%s, remote=%b)%n",
                            code, reason, remote);
                    bus.emit(new ClientEvent.Builder(ClientEventType.DISCONNECTED)
                            .message(reason).build());
                }

                @Override
                public void onError(Exception ex) {
                    System.err.println("[Client] WS error: " + ex.getMessage());
                    bus.emit(ClientEvent.error(ex.getMessage(), ex));
                }
            };

            if (config.connectTimeoutMs > 0) {
                ws.setConnectionLostTimeout(config.connectTimeoutMs / 1000);
            }
            ws.connect();

        } catch (Exception e) {
            bus.emit(ClientEvent.error("Failed to create WebSocket: " + e.getMessage(), e));
        }
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
                JSONObject joinedPlayer = payload.optJSONObject("player");
                if (joinedPlayer != null) session.onPlayerJoined(joinedPlayer);
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
                JSONObject reconnPlayer = payload.optJSONObject("player");
                if (reconnPlayer != null) session.onPlayerReconnected(reconnPlayer);
                bus.emit(ClientEvent.of(ClientEventType.PLAYER_RECONNECTED, payload));
                break;
            }

            case PLAYER_UPDATE: {
                JSONObject updatedPlayer = payload.optJSONObject("player");
                if (updatedPlayer != null) session.onPlayerUpdate(updatedPlayer);
                bus.emit(ClientEvent.of(ClientEventType.PLAYER_UPDATE, payload));
                break;
            }

            case ROOM_UPDATE: {
                JSONObject updatedRoom = payload.optJSONObject("room");
                if (updatedRoom != null) session.onRoomUpdate(updatedRoom);
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
                // Merge state change into current room if we're in it
                if (payload.optString("roomId", "").equals(session.getRoomId())) {
                    session.onRoomUpdate(payload);
                }
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

        // Shorthand setters — mutate config internally via copyConfig
        public Builder host(String host)   { this.config = copyConfig(b -> b.host(host));   return this; }
        public Builder port(int port)      { this.config = copyConfig(b -> b.port(port));   return this; }
        public Builder useSsl(boolean ssl) { this.config = copyConfig(b -> b.useSsl(ssl));  return this; }

        /** Player display name sent with JOIN. */
        public Builder name(String name) { this.name = name; return this; }

        /** Auto-join this room immediately after WELCOME. */
        public Builder roomId(String roomId) { this.roomId = roomId; return this; }

        /**
         * Token from a previous session — sends JOIN with this token so the server
         * can restore the player's slot.
         */
        public Builder reconnectToken(String token) { this.reconnectToken = token; return this; }

        /**
         * Admin token — automatically sends {@code ADMIN_AUTH} immediately after connecting,
         * before the JOIN message.
         */
        public Builder adminToken(String token) { this.adminToken = token; return this; }

        public SocketBaseClient build() { return new SocketBaseClient(this); }

        private ClientConfig copyConfig(java.util.function.Consumer<ClientConfig.Builder> fn) {
            ClientConfig.Builder b = new ClientConfig.Builder()
                    .host(config.host)
                    .port(config.port)
                    .useSsl(config.useSsl)
                    .connectTimeoutMs(config.connectTimeoutMs)
                    .reconnectTimeoutMs(config.reconnectTimeoutMs);
            fn.accept(b);
            return b.build();
        }
    }
}

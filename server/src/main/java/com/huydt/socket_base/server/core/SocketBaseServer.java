package com.huydt.socket_base.server.core;

import com.huydt.socket_base.server.admin.AdminService;
import com.huydt.socket_base.server.event.EventBus;
import com.huydt.socket_base.server.event.EventType;
import com.huydt.socket_base.server.event.ServerEvent;
import com.huydt.socket_base.server.network.BaseWebSocketServer;
import com.huydt.socket_base.server.network.MessageDispatcher;
import com.huydt.socket_base.server.network.TcpClientHandler;

import java.io.IOException;
import java.net.ServerSocket;
import java.net.Socket;
import java.util.UUID;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import java.util.concurrent.atomic.AtomicBoolean;
import java.util.concurrent.atomic.AtomicLong;

/**
 * Entry-point for the {@code com.huydt.socket_base.server} SDK.
 *
 * <h3>Minimal usage</h3>
 * <pre>
 * SocketBaseServer server = new SocketBaseServer.Builder().build();
 * new Thread(server::startSafe).start();
 * </pre>
 *
 * <h3>Fully configured</h3>
 * <pre>
 * SocketBaseServer server = new SocketBaseServer.Builder()
 *     .config(new ServerConfig.Builder()
 *         .port(9000)
 *         .wsPort(9001)
 *         .transport(TransportMode.BOTH)
 *         .adminToken("my-secret")
 *         .reconnectTimeoutMs(30_000)
 *         .autoCreateDefaultRoom(true)
 *         .build())
 *     .dispatcher(myCustomDispatcher)  // optional — extend MessageDispatcher
 *     .build();
 *
 * // Subscribe to events
 * server.getEventBus().on(EventType.PLAYER_JOINED, e ->
 *     System.out.println("Welcome: " + e.getPlayer().getName()));
 *
 * // Start (blocks calling thread — run on a background thread)
 * server.startSafe();
 * </pre>
 *
 * <h3>Admin API</h3>
 * <pre>
 * AdminService admin = server.getAdmin();
 * admin.kick("playerId", "AFK");
 * admin.broadcast("roomId", "ROUND_START", null);
 * admin.changeRoomState("roomId", Room.RoomState.PLAYING);
 * </pre>
 *
 * <h3>CLI args (pass from {@code main})</h3>
 * <pre>
 * --tcp               TCP only
 * --ws                WebSocket only
 * --both              TCP + WebSocket (default)
 * --port &lt;n&gt;          TCP port  (default 9000)
 * --wsport &lt;n&gt;        WS port   (default tcpPort+1)
 * --admin-token &lt;s&gt;   Admin secret token
 * </pre>
 */
public class SocketBaseServer {

    private final ServerConfig          config;
    private final EventBus              bus;
    private final PlayerManager         playerManager;
    private final RoomManager           roomManager;
    private final MessageDispatcher     dispatcher;
    private final AdminService          admin;

    private final ExecutorService       threadPool = Executors.newCachedThreadPool();
    private final AtomicBoolean         running    = new AtomicBoolean(false);
    private final AtomicLong            startedAt  = new AtomicLong(0);

    private ServerSocket                tcpSocket;
    private BaseWebSocketServer         wsServer;

    // ── Constructor ───────────────────────────────────────────────────

    private SocketBaseServer(Builder b) {
        this(b.config, b.dispatcher);
    }

    /**
     * Subclass-friendly constructor — pass a {@link ServerConfig} and optionally
     * a custom dispatcher. Override {@link PlayerManager#createPlayer} and
     * {@link RoomManager#newRoom} in subclass managers injected via
     * {@link Builder#playerManager} / {@link Builder#roomManager}.
     */
    public SocketBaseServer(ServerConfig config) {
        this(config, null);
    }

    public SocketBaseServer(ServerConfig config, MessageDispatcher dispatcher) {
        this.config        = config;
        this.bus           = new EventBus();
        this.playerManager = createPlayerManager(config, bus);
        this.roomManager   = createRoomManager(config, playerManager, bus);

        MessageDispatcher resolvedDispatcher = dispatcher != null
                ? dispatcher
                : createDispatcher(playerManager, roomManager, bus, config.adminToken);

        if (resolvedDispatcher == null) {
            throw new IllegalStateException(
                "[SocketBaseServer] createDispatcher() returned null. " +
                "Override createDispatcher() to return a valid MessageDispatcher instance.");
        }
        this.dispatcher = resolvedDispatcher;
        this.admin         = new AdminService(playerManager, roomManager);

        // Print admin token if auto-generated
        System.out.println("[SocketBaseServer] Admin token: " + config.adminToken);
        System.out.println("[SocketBaseServer] " + config);

        // Auto-create default room
        if (config.autoCreateDefaultRoom) {
            roomManager.createRoom("default", "Default Room", config.maxPlayersPerRoom);
        }

        // WS server
        if (config.wsPort > 0 &&
            (config.transport == TransportMode.WS || config.transport == TransportMode.BOTH)) {
            this.wsServer = new BaseWebSocketServer(config.wsPort, this.dispatcher);
        }
    }

    // ── Lifecycle ─────────────────────────────────────────────────────

    // ── Extension points ──────────────────────────────────────────────

    /**
     * Override to supply a custom {@link PlayerManager} subclass.
     * <pre>
     * &#64;Override
     * protected PlayerManager createPlayerManager(ServerConfig c, EventBus b) {
     *     return new LotoPlayerManager(c, b);
     * }
     * </pre>
     */
    protected PlayerManager createPlayerManager(ServerConfig config, EventBus bus) {
        return new PlayerManager(config, bus);
    }

    /**
     * Override to supply a custom {@link RoomManager} subclass.
     */
    protected RoomManager createRoomManager(ServerConfig config, PlayerManager pm, EventBus bus) {
        return new RoomManager(config, pm, bus);
    }

    /**
     * Override to supply a custom {@link MessageDispatcher} subclass.
     * <pre>
     * &#64;Override
     * protected MessageDispatcher createDispatcher(...) {
     *     return new LotoDispatcher(playerManager, roomManager, bus, adminToken);
     * }
     * </pre>
     */
    protected MessageDispatcher createDispatcher(PlayerManager pm, RoomManager rm,
                                                  EventBus bus, String adminToken) {
        return new MessageDispatcher(pm, rm, bus, adminToken);
    }

    // ── Lifecycle ─────────────────────────────────────────────────────

    /**
     * Starts the server. Blocks the calling thread while accepting connections.
     * Run on a background thread (mandatory on Android).
     */
    public void start() throws IOException {
        running.set(true);
        startedAt.set(System.currentTimeMillis());

        // Start WebSocket
        if (wsServer != null) {
            Thread wsThread = new Thread(wsServer::startSafe, "ws-server");
            wsThread.setDaemon(true);
            wsThread.start();
        }

        // WS-only mode: skip TCP accept loop but keep thread alive
        if (config.transport == TransportMode.WS) {
            System.out.println("[SocketBaseServer] WS-only mode — TCP accept loop disabled");
            while (running.get()) {
                try { Thread.sleep(1000); } catch (InterruptedException e) { break; }
            }
            return;
        }

        // TCP accept loop
        tcpSocket = new ServerSocket(config.tcpPort);
        System.out.println("[SocketBaseServer] TCP listening on :" + config.tcpPort);
        bus.emit(ServerEvent.of(EventType.SERVER_STARTED,
                "port=" + config.tcpPort + " ws=" + config.wsPort));

        while (running.get()) {
            try {
                Socket socket = tcpSocket.accept();
                String connId = UUID.randomUUID().toString().replace("-", "").substring(0, 8);
                threadPool.execute(new TcpClientHandler(connId, socket, dispatcher));
            } catch (IOException e) {
                if (!running.get()) break;
                System.err.println("[SocketBaseServer] Accept error: " + e.getMessage());
            }
        }
    }

    /** Convenience wrapper — catches IOException so it can be passed to Thread / Runnable. */
    public void startSafe() {
        try { start(); } catch (IOException e) {
            System.err.println("[SocketBaseServer] Failed to start: " + e.getMessage());
        }
    }

    /** Stops the server and cleans up all resources. */
    public void stop() {
        running.set(false);
        roomManager.shutdownAll();
        playerManager.shutdown();
        threadPool.shutdownNow();
        try { if (tcpSocket != null) tcpSocket.close(); } catch (IOException ignored) {}
        if (wsServer != null) {
            try { wsServer.stop(); } catch (Exception ignored) {}
        }
        bus.emit(ServerEvent.of(EventType.SERVER_STOPPED));
        System.out.println("[SocketBaseServer] Stopped.");
    }

    // ── Accessors ─────────────────────────────────────────────────────

    public ServerConfig     getConfig()        { return config; }
    public EventBus         getEventBus()      { return bus; }
    public PlayerManager    getPlayerManager() { return playerManager; }
    public RoomManager      getRoomManager()   { return roomManager; }
    public AdminService     getAdmin()         { return admin; }
    public boolean          isRunning()        { return running.get(); }
    public long             getUptimeMs()      { return startedAt.get() > 0 ? System.currentTimeMillis() - startedAt.get() : 0; }

    // ── CLI factory ───────────────────────────────────────────────────

    /**
     * Build a server from command-line args.
     * Supports: --tcp, --ws, --both, --port N, --wsport N, --admin-token S
     */
    public static SocketBaseServer fromArgs(String[] args) {
        ServerConfig.Builder cfg = new ServerConfig.Builder();
        for (int i = 0; i < args.length; i++) {
            switch (args[i].toLowerCase()) {
                case "--tcp":   cfg.transport(TransportMode.TCP);  break;
                case "--ws":    cfg.transport(TransportMode.WS);   break;
                case "--both":  cfg.transport(TransportMode.BOTH); break;
                case "--port":
                    if (i + 1 < args.length) cfg.port(Integer.parseInt(args[++i]));
                    break;
                case "--wsport":
                    if (i + 1 < args.length) cfg.wsPort(Integer.parseInt(args[++i]));
                    break;
                case "--admin-token":
                    if (i + 1 < args.length) cfg.adminToken(args[++i]);
                    break;
            }
        }
        return new Builder().config(cfg.build()).build();
    }

    // ── Builder ───────────────────────────────────────────────────────

    public static class Builder {
        private ServerConfig       config     = new ServerConfig.Builder().build();
        private MessageDispatcher  dispatcher = null;

        /** Pass a fully built config. */
        public Builder config(ServerConfig config) { this.config = config; return this; }

        /**
         * Supply a custom dispatcher (extend {@link MessageDispatcher} to add
         * game-specific message handling).
         */
        public Builder dispatcher(MessageDispatcher dispatcher) { this.dispatcher = dispatcher; return this; }

        public SocketBaseServer build() { return new SocketBaseServer(this); }
    }
}

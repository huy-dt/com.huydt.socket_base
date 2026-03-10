package com.huydt.loto_server_sdk;

import com.huydt.socket_base.server.core.ServerConfig;
import com.huydt.socket_base.server.core.TransportMode;

/**
 * CLI entry point.
 *
 * Usage:
 *   java -jar loto_server_sdk.jar [options]
 *
 * Options:
 *   --port      <n>        TCP port           (default 9000)
 *   --wsport    <n>        WebSocket port     (default 9001)
 *   --transport tcp|ws|both                   (default ws)
 *   --admin-token <s>      Admin token        (default: auto-generated UUID)
 *   --reconnect-ms  <n>    Reconnect window   (default 30000)
 *   --max-players   <n>    Max players/room   (default 0 = unlimited)
 *   --max-rooms     <n>    Max rooms          (default 0 = unlimited)
 *   --persist       <path> Persistence file   (default: none)
 */
public class Main {

    public static void main(String[] args) {
        // ── Parse CLI args ────────────────────────────────────────────────
        int    port           = 9000;
        int    wsPort         = 9001;
        String transport      = "ws";
        String adminToken     = null;
        int    reconnectMs    = 30_000;
        int    maxPlayers     = 0;
        int    maxRooms       = 0;
        String persistPath    = null;

        for (int i = 0; i < args.length; i++) {
            switch (args[i]) {
                case "--port":          port        = Integer.parseInt(args[++i]); break;
                case "--wsport":        wsPort      = Integer.parseInt(args[++i]); break;
                case "--transport":     transport   = args[++i];                   break;
                case "--admin-token":   adminToken  = args[++i];                   break;
                case "--reconnect-ms":  reconnectMs = Integer.parseInt(args[++i]);   break;
                case "--max-players":   maxPlayers  = Integer.parseInt(args[++i]); break;
                case "--max-rooms":     maxRooms    = Integer.parseInt(args[++i]); break;
                case "--persist":       persistPath = args[++i];                   break;
                default:
                    System.err.println("[WARN] Unknown argument: " + args[i]);
            }
        }

        // ── Build TransportMode ───────────────────────────────────────────
        TransportMode mode;
        switch (transport.toLowerCase()) {
            case "tcp":  mode = TransportMode.TCP;  break;
            case "both": mode = TransportMode.BOTH; break;
            default:     mode = TransportMode.WS;   break;
        }

        // ── Build ServerConfig ────────────────────────────────────────────
        ServerConfig.Builder builder = new ServerConfig.Builder()
                .port(port)
                .wsPort(wsPort)
                .transport(mode)
                .reconnectTimeoutMs(reconnectMs)
                .maxPlayersPerRoom(maxPlayers)
                .maxRooms(maxRooms)
                .autoCreateDefaultRoom(false);

        if (adminToken != null)  builder.adminToken(adminToken);
        if (persistPath != null) builder.persistPath(persistPath);

        ServerConfig config = builder.build();

        // ── Print startup info ────────────────────────────────────────────
        System.out.println("====================================");
        System.out.println("  Loto Server SDK");
        System.out.println("====================================");
        System.out.println("  Transport : " + mode);
        if (mode != TransportMode.WS)  System.out.println("  TCP port  : " + port);
        if (mode != TransportMode.TCP) System.out.println("  WS  port  : " + wsPort);
        System.out.println("  Reconnect : " + reconnectMs + " ms");
        System.out.println("  Max players/room : " + (maxPlayers == 0 ? "unlimited" : maxPlayers));
        System.out.println("  Max rooms        : " + (maxRooms   == 0 ? "unlimited" : maxRooms));
        System.out.println("  Persist   : " + (persistPath != null ? persistPath : "disabled"));
        System.out.println("====================================");

        // ── Start server ──────────────────────────────────────────────────
        LotoServer server = new LotoServer(config);

        Runtime.getRuntime().addShutdownHook(new Thread(() -> {
            System.out.println("\n[INFO] Shutting down...");
            server.stop();
        }));

        server.startSafe();
    }
}

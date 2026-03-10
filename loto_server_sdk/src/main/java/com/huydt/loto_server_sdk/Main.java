package com.huydt.loto_server_sdk;

import com.huydt.socket_base.server.admin.AdminService;
import com.huydt.socket_base.server.core.ServerConfig;
import com.huydt.socket_base.server.core.TransportMode;
import com.huydt.socket_base.server.event.EventType;
import com.huydt.socket_base.server.model.Player;
import com.huydt.socket_base.server.model.Room;

import java.util.Scanner;

/**
 * CLI entry point for the Loto server.
 *
 * Usage:
 *   java -jar loto_server_sdk.jar [options]
 *
 * Options:
 *   --port           <n>     TCP port             (default 9000)
 *   --wsport         <n>     WebSocket port       (default 9001)
 *   --transport      tcp|ws|both  (default ws)
 *   --admin-token    <s>     Admin token          (default: auto UUID)
 *   --reconnect-ms   <n>     Reconnect window ms  (default 30000)
 *   --max-players    <n>     Max players/room     (default 0 = unlimited)
 *   --max-rooms      <n>     Max rooms            (default 0 = unlimited)
 *   --price-per-page <n>     Default price/page   (default 1000)
 *   --auto-reset-ms  <n>     Auto reset delay ms  (default 0 = off)
 *   --auto-start-ms  <n>     Auto start delay ms  (default 0 = off)
 *   --persist        <path>  Persistence file     (default: none)
 *
 * Interactive admin commands (type after server starts):
 *   create <name> [maxPlayers]    Create a room
 *   close  <roomId>               Close a room
 *   start  <roomId>               Force start game in room
 *   reset  <roomId>               Reset room to WAITING
 *   kick   <playerId> [reason]    Kick a player
 *   ban    <playerId> [reason]    Ban a player
 *   rooms                         List all rooms
 *   players                       List all players
 *   stats                         Show server stats
 *   help                          Show this help
 */
public class Main {

    // Default game config — overridable via CLI
    private static long defaultPricePerPage = 1_000;
    private static long defaultAutoResetMs  = 0;
    private static long defaultAutoStartMs  = 0;

    public static void main(String[] args) throws InterruptedException {
        // ── Parse CLI args ────────────────────────────────────────────────
        int    port        = 9000;
        int    wsPort      = 9001;
        String transport   = "ws";
        String adminToken  = null;
        int    reconnectMs = 30_000;
        int    maxPlayers  = 0;
        int    maxRooms    = 0;
        String persistPath = null;

        for (int i = 0; i < args.length; i++) {
            switch (args[i]) {
                case "--port":           port               = Integer.parseInt(args[++i]); break;
                case "--wsport":         wsPort             = Integer.parseInt(args[++i]); break;
                case "--transport":      transport          = args[++i];                   break;
                case "--admin-token":    adminToken         = args[++i];                   break;
                case "--reconnect-ms":   reconnectMs        = Integer.parseInt(args[++i]); break;
                case "--max-players":    maxPlayers         = Integer.parseInt(args[++i]); break;
                case "--max-rooms":      maxRooms           = Integer.parseInt(args[++i]); break;
                case "--price-per-page": defaultPricePerPage = Long.parseLong(args[++i]);  break;
                case "--auto-reset-ms":  defaultAutoResetMs  = Long.parseLong(args[++i]);  break;
                case "--auto-start-ms":  defaultAutoStartMs  = Long.parseLong(args[++i]);  break;
                case "--persist":        persistPath        = args[++i];                   break;
                default:
                    System.err.println("[WARN] Unknown argument: " + args[i]);
            }
        }

        TransportMode mode;
        switch (transport.toLowerCase()) {
            case "tcp":  mode = TransportMode.TCP;  break;
            case "both": mode = TransportMode.BOTH; break;
            default:     mode = TransportMode.WS;   break;
        }

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

        // ── Build & wire server ───────────────────────────────────────────
        LotoServer server = new LotoServer(config);
        AdminService admin = server.getAdmin();

        // Event hooks
        server.getEventBus().on(EventType.SERVER_STARTED, e -> {
            System.out.println("\n[SERVER] Started — admin token: " + config.adminToken);
            printHelp();
        });

        server.getEventBus().on(EventType.PLAYER_JOINED, e -> {
            Player p = e.getPlayer();
            System.out.println("[+] Player joined: " + p.getName() + " (" + p.getId() + ")");
        });

        server.getEventBus().on(EventType.PLAYER_DISCONNECTED, e -> {
            Player p = e.getPlayer();
            System.out.println("[~] Player disconnected: " + p.getName() + " (ghost)");
        });

        server.getEventBus().on(EventType.PLAYER_RECONNECTED, e -> {
            Player p = e.getPlayer();
            System.out.println("[~] Player reconnected: " + p.getName());
        });

        server.getEventBus().on(EventType.PLAYER_LEFT, e -> {
            Player p = e.getPlayer();
            System.out.println("[-] Player left permanently: " + p.getName());
        });

        server.getEventBus().on(EventType.PLAYER_KICKED, e ->
                System.out.println("[!] Kicked: " + e.getPlayer().getName()));

        server.getEventBus().on(EventType.PLAYER_BANNED, e ->
                System.out.println("[!] Banned: " + e.getPlayer().getName()));

        server.getEventBus().on(EventType.ROOM_CREATED, e -> {
            Room r = e.getRoom();
            // Apply default loto config to new room
            if (r instanceof com.huydt.loto_server_sdk.model.LotoRoom) {
                com.huydt.loto_server_sdk.model.LotoRoom lr =
                        (com.huydt.loto_server_sdk.model.LotoRoom) r;
                lr.pricePerPage  = defaultPricePerPage;
                lr.timeAutoReset = defaultAutoResetMs;
                lr.timeAutoStart = defaultAutoStartMs;
            }
            System.out.println("[R+] Room created: " + r.getName() + " (" + r.getId() + ")");
        });

        server.getEventBus().on(EventType.ROOM_CLOSED, e ->
                System.out.println("[R-] Room closed: " + e.getRoom().getName()));

        server.getEventBus().on(EventType.ROOM_STATE_CHANGED, e ->
                System.out.println("[R~] Room state: " + e.getRoom().getName()
                        + " → " + e.getRoom().getState()));

        // ── Print startup banner ──────────────────────────────────────────
        System.out.println("╔══════════════════════════════════╗");
        System.out.println("║       Loto Server SDK            ║");
        System.out.println("╠══════════════════════════════════╣");
        System.out.printf ("║  Transport   : %-18s║%n", mode);
        if (mode != TransportMode.WS)  System.out.printf("║  TCP port    : %-18d║%n", port);
        if (mode != TransportMode.TCP) System.out.printf("║  WS  port    : %-18d║%n", wsPort);
        System.out.printf ("║  Reconnect   : %-15dms║%n", reconnectMs);
        System.out.printf ("║  Price/page  : %-18d║%n", defaultPricePerPage);
        System.out.printf ("║  Auto-reset  : %-15s   ║%n",
                defaultAutoResetMs > 0 ? defaultAutoResetMs + "ms" : "off");
        System.out.printf ("║  Auto-start  : %-15s   ║%n",
                defaultAutoStartMs > 0 ? defaultAutoStartMs + "ms" : "off");
        System.out.println("╚══════════════════════════════════╝");
        System.out.println("Starting...");

        // ── Start server in background thread ─────────────────────────────
        Thread serverThread = new Thread(server::startSafe);
        serverThread.setDaemon(true);
        serverThread.start();

        Runtime.getRuntime().addShutdownHook(new Thread(() -> {
            System.out.println("\n[INFO] Shutting down...");
            server.stop();
        }));

        // ── Interactive admin console ─────────────────────────────────────
        Scanner scanner = new Scanner(System.in);
        while (scanner.hasNextLine()) {
            String line = scanner.nextLine().trim();
            if (line.isEmpty()) continue;
            String[] parts = line.split("\\s+", 3);
            String cmd = parts[0].toLowerCase();

            try {
                switch (cmd) {
                    case "create": {
                        String rName  = parts.length > 1 ? parts[1] : "Room-1";
                        int    rMax   = parts.length > 2 ? Integer.parseInt(parts[2]) : 8;
                        admin.createRoom(rName, rMax);
                        break;
                    }
                    case "close": {
                        requireArg(parts, 1, "close <roomId>");
                        admin.closeRoom(parts[1]);
                        break;
                    }
                    case "start": {
                        requireArg(parts, 1, "start <roomId>");
                        // Broadcast START_GAME signal — LotoDispatcher handles it
                        admin.changeRoomState(parts[1], Room.RoomState.STARTING);
                        // Direct engine start via broadcast
                        admin.broadcast(parts[1], "FORCE_START", new org.json.JSONObject());
                        System.out.println("[CMD] Force-starting room: " + parts[1]);
                        break;
                    }
                    case "reset": {
                        requireArg(parts, 1, "reset <roomId>");
                        admin.changeRoomState(parts[1], Room.RoomState.WAITING);
                        System.out.println("[CMD] Reset room: " + parts[1]);
                        break;
                    }
                    case "kick": {
                        requireArg(parts, 1, "kick <playerId> [reason]");
                        String reason = parts.length > 2 ? parts[2] : "Kicked by admin";
                        admin.kick(parts[1], reason);
                        break;
                    }
                    case "ban": {
                        requireArg(parts, 1, "ban <playerId> [reason]");
                        String reason = parts.length > 2 ? parts[2] : "Banned by admin";
                        admin.ban(parts[1], reason);
                        break;
                    }
                    case "unban": {
                        requireArg(parts, 1, "unban <playerName>");
                        admin.unban(parts[1]);
                        break;
                    }
                    case "rooms": {
                        System.out.println("── Rooms (" + admin.getRoomCount() + ") ──");
                        for (Room r : admin.getAllRooms()) {
                            System.out.printf("  [%s] %s  state=%-10s players=%d%n",
                                    r.getId(), r.getName(), r.getState(),
                                    r.getPlayers().size());
                        }
                        break;
                    }
                    case "players": {
                        System.out.println("── Players (" + admin.getPlayerCount() + ") ──");
                        for (Player p : admin.getAllPlayers()) {
                            System.out.printf("  [%s] %-12s room=%-10s connected=%b%n",
                                    p.getId(), p.getName(), p.getRoomId(), p.isConnected());
                        }
                        break;
                    }
                    case "stats": {
                        System.out.println("── Stats ──");
                        System.out.println("  Rooms  : " + admin.getRoomCount());
                        System.out.println("  Players: " + admin.getPlayerCount());
                        break;
                    }
                    case "help":
                        printHelp();
                        break;
                    default:
                        System.out.println("[?] Unknown command. Type 'help'");
                }
            } catch (Exception ex) {
                System.err.println("[ERR] " + ex.getMessage());
            }
        }
    }

    private static void requireArg(String[] parts, int idx, String usage) {
        if (parts.length <= idx)
            throw new IllegalArgumentException("Usage: " + usage);
    }

    private static void printHelp() {
        System.out.println("── Admin commands ──────────────────────────────");
        System.out.println("  create <name> [maxPlayers]   Create room");
        System.out.println("  close  <roomId>              Close room");
        System.out.println("  start  <roomId>              Force start game");
        System.out.println("  reset  <roomId>              Reset to WAITING");
        System.out.println("  kick   <playerId> [reason]   Kick player");
        System.out.println("  ban    <playerId> [reason]   Ban player");
        System.out.println("  unban  <name>                Unban player");
        System.out.println("  rooms                        List rooms");
        System.out.println("  players                      List players");
        System.out.println("  stats                        Server stats");
        System.out.println("────────────────────────────────────────────────");
    }
}

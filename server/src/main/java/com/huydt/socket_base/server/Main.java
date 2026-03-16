package com.huydt.socket_base.server;

import com.huydt.socket_base.server.admin.AdminService;
import com.huydt.socket_base.server.core.ServerConfig;
import com.huydt.socket_base.server.core.SocketBaseServer;
import com.huydt.socket_base.server.core.TransportMode;
import com.huydt.socket_base.server.event.EventType;
import com.huydt.socket_base.server.model.Player;
import com.huydt.socket_base.server.model.Room;

import java.util.Collection;
import java.util.Scanner;

/**
 * Standalone entry point for {@code socket_base}.
 *
 * <h3>Usage</h3>
 * <pre>
 * java -jar socket_base.jar [options]
 *
 * Options:
 *   --tcp                   TCP only          (default: BOTH)
 *   --ws                    WebSocket only
 *   --both                  TCP + WebSocket
 *   --port      &lt;n&gt;         TCP port          (default: 9000)
 *   --wsport    &lt;n&gt;         WebSocket port    (default: tcpPort + 1)
 *   --admin-token &lt;secret&gt;  Admin token       (default: auto-generated UUID)
 *   --reconnect-ms &lt;n&gt;      Reconnect window  (default: 30000 ms)
 *   --max-players &lt;n&gt;       Max players/room  (default: 0 = unlimited)
 *   --max-rooms   &lt;n&gt;       Max rooms         (default: 0 = unlimited)
 *   --no-default-room       Skip auto-creating the "default" room
 *   --persist   &lt;path&gt;      JSON save file    (default: disabled)
 *   --show-appinfo          Print full server info on startup
 *   --no-console            Disable interactive admin console
 *   --help                  Print this help
 * </pre>
 */
public class Main {

    private static SocketBaseServer server;
    private static boolean          showConsole = true;

    public static void main(String[] args) {
        if (hasFlag(args, "--help") || hasFlag(args, "-h")) { printHelp(); return; }

        boolean showAppInfo = hasFlag(args, "--show-appinfo");
        showConsole         = !hasFlag(args, "--no-console");

        server = new SocketBaseServer.Builder().config(parseArgs(args)).build();
        registerEventListeners();

        Runtime.getRuntime().addShutdownHook(new Thread(() -> {
            System.out.println("\n[Main] Shutting down…");
            server.stop();
        }, "shutdown-hook"));

        if (showAppInfo) printAppInfo(server.getConfig());

        if (showConsole) {
            Thread t = new Thread(Main::runAdminConsole, "admin-console");
            t.setDaemon(true);
            t.start();
        }

        server.startSafe();
    }

    // ── Event hooks ───────────────────────────────────────────────────

    private static void registerEventListeners() {
        ServerConfig cfg = server.getConfig();
        server.getEventBus()
            .on(EventType.SERVER_STARTED,      e -> System.out.println("[Event] Server started"))
            .on(EventType.SERVER_STOPPED,      e -> System.out.println("[Event] Server stopped"))
            .on(EventType.PLAYER_JOINED,       e -> System.out.printf(
                    "[Event] + %s joined (total: %d)%n",
                    e.getPlayer().getName(), server.getPlayerManager().getTotalCount()))
            .on(EventType.PLAYER_RECONNECTED,  e -> System.out.printf(
                    "[Event] ↩ %s reconnected%n", e.getPlayer().getName()))
            .on(EventType.PLAYER_DISCONNECTED, e -> System.out.printf(
                    "[Event] ~ %s disconnected (ghost, timeout: %dms)%n",
                    e.getPlayer().getName(), cfg.reconnectTimeoutMs))
            .on(EventType.PLAYER_LEFT,         e -> System.out.printf(
                    "[Event] - %s left permanently (total: %d)%n",
                    e.getPlayer().getName(), server.getPlayerManager().getTotalCount()))
            .on(EventType.PLAYER_KICKED,       e -> System.out.printf(
                    "[Event] kick %s: %s%n", e.getPlayer().getName(), e.getMessage()))
            .on(EventType.PLAYER_BANNED,       e -> System.out.println("[Event] ban " + e.getMessage()))
            .on(EventType.ROOM_CREATED,        e -> System.out.println("[Event] room created: " + e.getRoom()))
            .on(EventType.ROOM_CLOSED,         e -> System.out.println("[Event] room closed: " + e.getRoom()))
            .on(EventType.ROOM_STATE_CHANGED,  e -> System.out.println("[Event] room state: " + e.getRoom()))
            .on(EventType.ROOM_PLAYER_JOINED,  e -> System.out.printf(
                    "[Event] %s → room %s%n", e.getPlayer().getName(), e.getRoom().getName()))
            .on(EventType.ROOM_PLAYER_LEFT,    e -> System.out.printf(
                    "[Event] %s ← room %s%n", e.getPlayer().getName(), e.getRoom().getName()))
            .on(EventType.ADMIN_AUTH,          e -> System.out.println("[Event] admin auth: " + e.getMessage()))
            .on(EventType.ERROR,               e -> System.err.println("[Event] ERROR: " + e.getMessage()))
            .onError((event, ex) -> System.err.printf(
                    "[EventBus] Listener threw for %s: %s%n", event.getType(), ex.getMessage()));
    }

    // ── Admin console ─────────────────────────────────────────────────

    private static void runAdminConsole() {
        try { Thread.sleep(600); } catch (InterruptedException ignored) {}
        printConsoleBanner();

        Scanner sc = new Scanner(System.in);
        while (true) {
            System.out.print("admin> ");
            if (!sc.hasNextLine()) break;
            String line = sc.nextLine().trim();
            if (line.isEmpty()) continue;
            String[] parts = line.split("\\s+", 4);
            try {
                handleCommand(parts[0].toLowerCase(), parts);
            } catch (Exception e) {
                System.err.println("[Console] Error: " + e.getMessage());
            }
        }
    }

    private static void printConsoleBanner() {
        ServerConfig cfg = server.getConfig();
        System.out.println();
        System.out.println("╔══════════════════════════════════════════════════════════╗");
        System.out.println("║                  socket_base  ·  ADMIN CONSOLE          ║");
        System.out.println("╠══════════════════════════════════════════════════════════╣");
        System.out.printf( "║  Transport   : %-42s║%n", cfg.transport);
        System.out.printf( "║  TCP port    : %-42s║%n", cfg.tcpPort);
        System.out.printf( "║  WS  port    : %-42s║%n", cfg.wsPort > 0 ? cfg.wsPort : "disabled");
        System.out.printf( "║  Admin token : %-42s║%n", cfg.adminToken);
        System.out.printf( "║  Reconnect   : %-42s║%n", cfg.reconnectTimeoutMs + " ms");
        System.out.printf( "║  Max players : %-42s║%n", cfg.maxPlayersPerRoom == 0 ? "unlimited" : cfg.maxPlayersPerRoom);
        System.out.printf( "║  Max rooms   : %-42s║%n", cfg.maxRooms == 0 ? "unlimited" : cfg.maxRooms);
        System.out.printf( "║  Persistence : %-42s║%n", cfg.persistPath != null ? cfg.persistPath : "disabled");
        System.out.println("╠══════════════════════════════════════════════════════════╣");
        System.out.printf( "║  Players     : %-42s║%n", server.getAdmin().getPlayerCount());
        System.out.printf( "║  Rooms       : %-42s║%n", server.getAdmin().getRoomCount());
        System.out.println("╠══════════════════════════════════════════════════════════╣");
        System.out.println("║  Type 'help' for available commands                     ║");
        System.out.println("╚══════════════════════════════════════════════════════════╝");
        System.out.println();
    }

    private static void handleCommand(String cmd, String[] parts) {
        AdminService admin = server.getAdmin();

        switch (cmd) {
            case "help":   printConsoleHelp();  break;
            case "info":   printRuntimeInfo();  break;
            case "token":  System.out.println("  Admin token: " + server.getConfig().adminToken); break;

            case "rooms": {
                Collection<Room> rooms = admin.getAllRooms();
                if (rooms.isEmpty()) { System.out.println("  (no rooms)"); break; }
                System.out.printf("  %-20s %-20s %-10s %-8s %-8s%n", "ID", "NAME", "STATE", "PLAYERS", "MAX");
                System.out.println("  " + "-".repeat(68));
                for (Room r : rooms) {
                    System.out.printf("  %-20s %-20s %-10s %-8d %-8s%n",
                            r.getId(), r.getName(), r.getState().name(),
                            r.getPlayerCount(),
                            r.getMaxPlayers() == 0 ? "\u221e" : r.getMaxPlayers());
                }
                System.out.println("  Total: " + rooms.size());
                break;
            }

            case "room-add": {
                if (parts.length < 2) { System.out.println("  Usage: room-add <name>"); break; }
                Room r = admin.createRoom(parts[1]);
                System.out.printf("  \u2713 Created room '%s' (id: %s)%n", r.getName(), r.getId());
                break;
            }

            case "room-remove": {
                if (parts.length < 2) { System.out.println("  Usage: room-remove <roomId>"); break; }
                Room r = admin.getRoom(parts[1]);
                if (r == null) { System.out.println("  Room not found: " + parts[1]); break; }
                admin.closeRoom(parts[1]);
                System.out.printf("  \u2713 Closed room '%s'%n", r.getName());
                break;
            }

            case "room-state": {
                if (parts.length < 3) {
                    System.out.println("  Usage: room-state <roomId> <WAITING|STARTING|PLAYING|PAUSED|ENDED>"); break;
                }
                Room r = admin.getRoom(parts[1]);
                if (r == null) { System.out.println("  Room not found: " + parts[1]); break; }
                try {
                    Room.RoomState state = Room.RoomState.valueOf(parts[2].toUpperCase());
                    admin.changeRoomState(parts[1], state);
                    System.out.printf("  \u2713 Room '%s' state \u2192 %s%n", r.getName(), state);
                } catch (IllegalArgumentException e) {
                    System.out.println("  Invalid state. Valid: WAITING, STARTING, PLAYING, PAUSED, ENDED");
                }
                break;
            }

            case "players": {
                Collection<Player> players = admin.getAllPlayers();
                if (players.isEmpty()) { System.out.println("  (no players online)"); break; }
                System.out.printf("  %-24s %-12s %-20s %-10s %-6s%n", "ID", "NAME", "ROOM", "STATUS", "ADMIN");
                System.out.println("  " + "-".repeat(74));
                for (Player p : players) {
                    System.out.printf("  %-24s %-12s %-20s %-10s %-6s%n",
                            p.getId(), p.getName(),
                            p.getRoomId() != null ? p.getRoomId() : "\u2014",
                            p.isConnected() ? "online" : "ghost",
                            p.isAdmin() ? "\u2713" : "");
                }
                System.out.println("  Total: " + players.size());
                break;
            }

            case "kick": {
                if (parts.length < 2) { System.out.println("  Usage: kick <playerId> [reason]"); break; }
                Player p = admin.getPlayer(parts[1]);
                if (p == null) { System.out.println("  Player not found: " + parts[1]); break; }
                String reason = parts.length >= 3 ? parts[2] : "Kicked by admin";
                admin.kick(parts[1], reason);
                System.out.printf("  \u2713 Kicked %s (%s)%n", p.getName(), reason);
                break;
            }

            case "disconnect": {
                if (parts.length < 2) { System.out.println("  Usage: disconnect <playerId>"); break; }
                Player p = admin.getPlayer(parts[1]);
                if (p == null) { System.out.println("  Player not found: " + parts[1]); break; }
                admin.kick(parts[1], "Disconnected by admin");
                System.out.printf("  \u2713 Disconnected %s%n", p.getName());
                break;
            }

            case "bans":
                System.out.println("  Banned IDs : " + server.getPlayerManager().getBannedIds());
                System.out.println("  Banned IPs : " + server.getPlayerManager().getBannedIps());
                break;

            case "ban": {
                if (parts.length < 2) { System.out.println("  Usage: ban <playerId> [reason]"); break; }
                Player p      = admin.getPlayer(parts[1]);
                String name   = p != null ? p.getName() : parts[1];
                String reason = parts.length >= 3 ? parts[2] : "Banned by admin";
                admin.ban(parts[1], reason);
                System.out.printf("  \u2713 Banned %s (%s)%n", name, reason);
                break;
            }

            case "unban": {
                if (parts.length < 2) { System.out.println("  Usage: unban <displayName>"); break; }
                admin.unban(parts[1]);
                System.out.println("  \u2713 Unbanned: " + parts[1]);
                break;
            }

            case "ban-ip": {
                if (parts.length < 2) { System.out.println("  Usage: ban-ip <ip>"); break; }
                admin.banIp(parts[1]);
                System.out.println("  \u2713 Banned IP: " + parts[1]);
                break;
            }

            case "unban-ip": {
                if (parts.length < 2) { System.out.println("  Usage: unban-ip <ip>"); break; }
                admin.unbanIp(parts[1]);
                System.out.println("  \u2713 Unbanned IP: " + parts[1]);
                break;
            }

            case "broadcast": {
                if (parts.length < 2) { System.out.println("  Usage: broadcast [roomId|-] <TAG> [message]"); break; }
                String roomId, tag, msg = null;
                if (parts.length == 2) {
                    roomId = null; tag = parts[1];
                } else {
                    boolean firstIsRoom = parts[1].equals("-") || admin.getRoom(parts[1]) != null;
                    roomId = firstIsRoom && !parts[1].equals("-") ? parts[1] : null;
                    tag    = firstIsRoom ? parts[2] : parts[1];
                    msg    = parts.length >= 4 ? parts[3] : (firstIsRoom ? null : parts.length >= 3 ? parts[2] : null);
                }
                org.json.JSONObject payload = msg != null ? new org.json.JSONObject().put("message", msg) : null;
                admin.broadcast(roomId, tag, payload);
                System.out.printf("  \u2713 Broadcast '%s' \u2192 %s%n", tag, roomId != null ? "room " + roomId : "ALL");
                break;
            }

            case "send": {
                if (parts.length < 3) { System.out.println("  Usage: send <playerId> <TAG> [message]"); break; }
                Player p = admin.getPlayer(parts[1]);
                if (p == null) { System.out.println("  Player not found: " + parts[1]); break; }
                org.json.JSONObject payload = parts.length >= 4
                        ? new org.json.JSONObject().put("message", parts[3]) : null;
                admin.sendTo(parts[1], parts[2], payload);
                System.out.printf("  \u2713 Sent '%s' to %s%n", parts[2], p.getName());
                break;
            }

            case "stop": case "exit": case "quit":
                System.out.println("[Console] Stopping server…");
                server.stop();
                System.exit(0);
                break;

            default:
                System.out.println("  Unknown command: '" + cmd + "'. Type 'help' for a list.");
        }
    }

    // ── Print helpers ─────────────────────────────────────────────────

    private static void printAppInfo(ServerConfig cfg) {
        System.out.println();
        System.out.println("╔══════════════════════════════════════════════════════╗");
        System.out.println("║                  socket_base server                 ║");
        System.out.println("╠══════════════════════════════════════════════════════╣");
        System.out.printf( "║  Transport  : %-38s║%n", cfg.transport);
        System.out.printf( "║  TCP port   : %-38s║%n", cfg.tcpPort);
        System.out.printf( "║  WS  port   : %-38s║%n", cfg.wsPort > 0 ? cfg.wsPort : "disabled");
        System.out.printf( "║  Admin token: %-38s║%n", cfg.adminToken);
        System.out.printf( "║  Reconnect  : %-38s║%n", cfg.reconnectTimeoutMs + " ms");
        System.out.printf( "║  Max players: %-38s║%n", cfg.maxPlayersPerRoom == 0 ? "unlimited" : cfg.maxPlayersPerRoom);
        System.out.printf( "║  Max rooms  : %-38s║%n", cfg.maxRooms == 0 ? "unlimited" : cfg.maxRooms);
        System.out.printf( "║  Persistence: %-38s║%n", cfg.persistPath != null ? cfg.persistPath : "disabled");
        System.out.println("╚══════════════════════════════════════════════════════╝");
        System.out.println();
    }

    private static void printRuntimeInfo() {
        ServerConfig cfg  = server.getConfig();
        long uptimeSec    = server.getUptimeMs() / 1000;
        System.out.println("\n  ── Server Info ─────────────────────────────────────");
        System.out.println("  Transport   : " + cfg.transport);
        System.out.printf( "  TCP port    : %d%n", cfg.tcpPort);
        System.out.printf( "  WS  port    : %s%n", cfg.wsPort > 0 ? cfg.wsPort : "disabled");
        System.out.println("  Admin token : " + cfg.adminToken);
        System.out.printf( "  Uptime      : %dh %02dm %02ds%n", uptimeSec/3600, (uptimeSec%3600)/60, uptimeSec%60);
        System.out.println("  Running     : " + server.isRunning());
        System.out.printf( "  Players     : %d%n", server.getAdmin().getPlayerCount());
        System.out.printf( "  Rooms       : %d%n", server.getAdmin().getRoomCount());
        System.out.printf( "  Banned IDs  : %d%n", server.getPlayerManager().getBannedIds().size());
        System.out.printf( "  Banned IPs  : %d%n", server.getPlayerManager().getBannedIps().size());
        System.out.println();
    }

    private static void printConsoleHelp() {
        System.out.println("\n  ── General ─────────────────────────────────────────────────────────");
        System.out.println("  help                               Show this help");
        System.out.println("  info                               Server info & uptime");
        System.out.println("  token                              Show admin token");
        System.out.println("\n  ── Rooms ───────────────────────────────────────────────────────────");
        System.out.println("  rooms                              List all rooms");
        System.out.println("  room-add    <name>                 Create a new room");
        System.out.println("  room-remove <roomId>               Close and remove a room");
        System.out.println("  room-state  <roomId> <state>       Set room state");
        System.out.println("              States: WAITING STARTING PLAYING PAUSED ENDED");
        System.out.println("\n  ── Players ─────────────────────────────────────────────────────────");
        System.out.println("  players                            List all players");
        System.out.println("  kick        <playerId> [reason]    Kick a player");
        System.out.println("  disconnect  <playerId>             Disconnect a player");
        System.out.println("\n  ── Bans ────────────────────────────────────────────────────────────");
        System.out.println("  bans                               Show all ban lists");
        System.out.println("  ban         <playerId> [reason]    Ban a player by ID");
        System.out.println("  unban       <displayName>          Unban by display name");
        System.out.println("  ban-ip      <ip>                   Ban an IP address");
        System.out.println("  unban-ip    <ip>                   Unban an IP address");
        System.out.println("\n  ── Messaging ───────────────────────────────────────────────────────");
        System.out.println("  broadcast   [roomId|-] <TAG> [msg] Broadcast event (- = all)");
        System.out.println("  send        <playerId> <TAG> [msg] Send event to player");
        System.out.println("\n  ── Server ──────────────────────────────────────────────────────────");
        System.out.println("  stop  (exit / quit)                Stop the server\n");
    }

    // ── CLI parsing ───────────────────────────────────────────────────

    private static ServerConfig parseArgs(String[] args) {
        ServerConfig.Builder cfg = new ServerConfig.Builder();
        for (int i = 0; i < args.length; i++) {
            switch (args[i].toLowerCase()) {
                case "--tcp":   cfg.transport(TransportMode.TCP);  break;
                case "--ws":    cfg.transport(TransportMode.WS);   break;
                case "--both":  cfg.transport(TransportMode.BOTH); break;
                case "--port":          cfg.port(intArg(args, ++i, "--port"));                     break;
                case "--ws-port":       cfg.wsPort(intArg(args, ++i, "--ws-port"));                break;
                case "--admin-token":   cfg.adminToken(strArg(args, ++i, "--admin-token"));        break;
                case "--reconnect-ms":  cfg.reconnectTimeoutMs(intArg(args, ++i, "--reconnect-ms")); break;
                case "--max-players":   cfg.maxPlayersPerRoom(intArg(args, ++i, "--max-players"));  break;
                case "--max-rooms":     cfg.maxRooms(intArg(args, ++i, "--max-rooms"));             break;
                case "--no-default-room": cfg.autoCreateDefaultRoom(false); break;
                case "--persist":       cfg.persistPath(strArg(args, ++i, "--persist"));           break;
                case "--show-appinfo": case "--no-console": break; // handled before parseArgs
                default: System.err.println("[Main] Unknown argument ignored: " + args[i]);
            }
        }
        return cfg.build();
    }

    private static int intArg(String[] args, int idx, String flag) {
        if (idx >= args.length) { System.err.println("[Main] Missing value for " + flag); System.exit(1); }
        try { return Integer.parseInt(args[idx]); }
        catch (NumberFormatException e) {
            System.err.println("[Main] " + flag + " requires an integer, got: " + args[idx]);
            System.exit(1); return 0;
        }
    }

    private static String strArg(String[] args, int idx, String flag) {
        if (idx >= args.length || args[idx].startsWith("--")) {
            System.err.println("[Main] Missing value for " + flag); System.exit(1);
        }
        return args[idx];
    }

    private static boolean hasFlag(String[] args, String flag) {
        for (String a : args) if (a.equalsIgnoreCase(flag)) return true;
        return false;
    }

    private static void printHelp() {
        System.out.println(
            "socket_base server\n\nUsage: java -jar socket_base.jar [options]\n\n" +
            "Transport:\n" +
            "  --tcp                 TCP only\n" +
            "  --ws                  WebSocket only\n" +
            "  --both                TCP + WebSocket\n\n" +
            "Network:\n" +
            "  --port      <n>       TCP listen port          (default: 9000)\n" +
            "  --ws-port   <n>       WebSocket port           (default: tcp+1)\n\n" +
            "Players / Rooms:\n" +
            "  --reconnect-ms <n>    Reconnect window in ms   (default: 30000)\n" +
            "  --max-players  <n>    Max players per room     (default: 0 = unlimited)\n" +
            "  --max-rooms    <n>    Max rooms                (default: 0 = unlimited)\n" +
            "  --no-default-room     Skip creating \"default\" room on startup\n\n" +
            "Admin:\n" +
            "  --admin-token <s>     Secret admin token       (default: random UUID)\n" +
            "  --show-appinfo        Print full config banner on startup\n" +
            "  --no-console          Disable interactive admin console\n\n" +
            "Persistence:\n" +
            "  --persist <path>      Path to JSON save file   (default: disabled)\n\n" +
            "  --help                Print this message\n\n" +
            "Examples:\n" +
            "  java -jar socket_base.jar\n" +
            "  java -jar socket_base.jar --show-appinfo --admin-token mysecret\n" +
            "  java -jar socket_base.jar --both --port 9000 --ws-port 9001 --reconnect-ms 10000\n" +
            "  java -jar socket_base.jar --ws --no-console\n"
        );
    }
}

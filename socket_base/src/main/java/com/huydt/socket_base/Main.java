package com.huydt.socket_base;

import com.huydt.socket_base.admin.AdminService;
import com.huydt.socket_base.core.ServerConfig;
import com.huydt.socket_base.core.SocketBaseServer;
import com.huydt.socket_base.core.TransportMode;
import com.huydt.socket_base.event.EventType;
import com.huydt.socket_base.model.Player;
import com.huydt.socket_base.model.Room;

import java.util.Arrays;
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
 *   --no-console            Disable interactive admin console (useful for headless/daemon)
 *   --help                  Print this help
 * </pre>
 *
 * <h3>Admin Console Commands</h3>
 * <pre>
 *   help                              Show all commands
 *   info                              Server info &amp; uptime
 *   list-players                      List all online players
 *   list-rooms                        List all rooms
 *   kick     &lt;playerId&gt; [reason]      Kick a player
 *   ban      &lt;playerId&gt; [reason]      Ban a player (by id)
 *   unban    &lt;name&gt;                   Unban a player (by display name)
 *   ban-ip   &lt;ip&gt;                     Ban an IP address
 *   unban-ip &lt;ip&gt;                     Unban an IP address
 *   create-room &lt;name&gt;               Create a new room
 *   close-room  &lt;roomId&gt;             Close a room
 *   room-state  &lt;roomId&gt; &lt;state&gt;    Change room state (WAITING/PLAYING/PAUSED/ENDED)
 *   broadcast   [roomId] &lt;tag&gt; [msg] Broadcast a custom event
 *   send        &lt;playerId&gt; &lt;tag&gt;     Send custom event to player
 *   banned                           List banned player IDs and IPs
 *   stop                             Gracefully stop the server
 * </pre>
 */
public class Main {

    private static SocketBaseServer server;
    private static boolean          showConsole = true;

    public static void main(String[] args) {
        if (hasFlag(args, "--help") || hasFlag(args, "-h")) {
            printHelp();
            return;
        }

        boolean showAppInfo = hasFlag(args, "--show-appinfo");
        showConsole         = !hasFlag(args, "--no-console");

        ServerConfig config = parseArgs(args);
        server = new SocketBaseServer.Builder().config(config).build();

        // ── Event hooks ───────────────────────────────────────────────
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
                        e.getPlayer().getName(), config.reconnectTimeoutMs))
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
                        "[Event] %s → room %s%n",
                        e.getPlayer().getName(), e.getRoom().getName()))
                .on(EventType.ROOM_PLAYER_LEFT,    e -> System.out.printf(
                        "[Event] %s ← room %s%n",
                        e.getPlayer().getName(), e.getRoom().getName()))
                .on(EventType.ADMIN_AUTH,          e -> System.out.println("[Event] admin auth: " + e.getMessage()))
                .on(EventType.ERROR,               e -> System.err.println("[Event] ERROR: " + e.getMessage()))
                .onError((event, ex) -> System.err.printf(
                        "[EventBus] Listener threw for %s: %s%n", event.getType(), ex.getMessage()));

        // ── Graceful shutdown ──────────────────────────────────────────
        Runtime.getRuntime().addShutdownHook(new Thread(() -> {
            System.out.println("\n[Main] Shutting down…");
            server.stop();
        }, "shutdown-hook"));

        // ── Optional full app info printout ───────────────────────────
        if (showAppInfo) {
            printAppInfo(config);
        }

        // ── Admin console on a separate thread ────────────────────────
        if (showConsole) {
            Thread consoleThread = new Thread(Main::runAdminConsole, "admin-console");
            consoleThread.setDaemon(true);
            consoleThread.start();
        }

        // ── Start server (blocks this thread) ─────────────────────────
        server.startSafe();
    }

    // ═══════════════════════════════════════════════════════════════════
    //  ADMIN CONSOLE
    // ═══════════════════════════════════════════════════════════════════

    private static void runAdminConsole() {
        // Brief pause so server-start banner prints first
        try { Thread.sleep(600); } catch (InterruptedException ignored) {}

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

        Scanner sc = new Scanner(System.in);
        while (true) {
            System.out.print("admin> ");
            if (!sc.hasNextLine()) break;
            String line = sc.nextLine().trim();
            if (line.isEmpty()) continue;

            String[] parts = line.split("\\s+", 4);
            String   cmd   = parts[0].toLowerCase();

            try {
                handleCommand(cmd, parts);
            } catch (Exception e) {
                System.err.println("[Console] Error: " + e.getMessage());
            }
        }
    }

    private static void handleCommand(String cmd, String[] parts) {
        AdminService admin = server.getAdmin();

        switch (cmd) {

            // ── Help ──────────────────────────────────────────────────
            case "help":
                printConsoleHelp();
                break;

            // ── Server info ───────────────────────────────────────────
            case "info":
                printRuntimeInfo();
                break;

            // ── List players ──────────────────────────────────────────
            case "list-players":
            case "players": {
                Collection<Player> players = admin.getAllPlayers();
                if (players.isEmpty()) {
                    System.out.println("  (no players online)");
                } else {
                    System.out.printf("  %-24s %-12s %-20s %-10s %-10s%n",
                            "ID", "NAME", "ROOM", "CONNECTED", "ADMIN");
                    System.out.println("  " + "-".repeat(78));
                    for (Player p : players) {
                        System.out.printf("  %-24s %-12s %-20s %-10s %-10s%n",
                                p.getId(),
                                p.getName(),
                                p.getRoomId() != null ? p.getRoomId() : "—",
                                p.isConnected() ? "✓" : "ghost",
                                p.isAdmin() ? "✓" : "");
                    }
                    System.out.println("  Total: " + players.size());
                }
                break;
            }

            // ── List rooms ────────────────────────────────────────────
            case "list-rooms":
            case "rooms": {
                Collection<Room> rooms = admin.getAllRooms();
                if (rooms.isEmpty()) {
                    System.out.println("  (no rooms)");
                } else {
                    System.out.printf("  %-20s %-20s %-10s %-8s %-8s%n",
                            "ID", "NAME", "STATE", "PLAYERS", "MAX");
                    System.out.println("  " + "-".repeat(68));
                    for (Room r : rooms) {
                        System.out.printf("  %-20s %-20s %-10s %-8d %-8s%n",
                                r.getId(),
                                r.getName(),
                                r.getState().name(),
                                r.getPlayerCount(),
                                r.getMaxPlayers() == 0 ? "∞" : String.valueOf(r.getMaxPlayers()));
                    }
                    System.out.println("  Total: " + rooms.size());
                }
                break;
            }

            // ── Kick ──────────────────────────────────────────────────
            case "kick": {
                if (parts.length < 2) { System.out.println("  Usage: kick <playerId> [reason]"); break; }
                String playerId = parts[1];
                String reason   = parts.length >= 3 ? parts[2] : "Kicked by admin";
                Player p        = admin.getPlayer(playerId);
                if (p == null) { System.out.println("  Player not found: " + playerId); break; }
                admin.kick(playerId, reason);
                System.out.printf("  ✓ Kicked %s (%s)%n", p.getName(), reason);
                break;
            }

            // ── Ban ───────────────────────────────────────────────────
            case "ban": {
                if (parts.length < 2) { System.out.println("  Usage: ban <playerId> [reason]"); break; }
                String playerId = parts[1];
                String reason   = parts.length >= 3 ? parts[2] : "Banned by admin";
                Player p        = admin.getPlayer(playerId);
                String name     = p != null ? p.getName() : playerId;
                admin.ban(playerId, reason);
                System.out.printf("  ✓ Banned %s (%s)%n", name, reason);
                break;
            }

            // ── Unban ─────────────────────────────────────────────────
            case "unban": {
                if (parts.length < 2) { System.out.println("  Usage: unban <displayName>"); break; }
                admin.unban(parts[1]);
                System.out.println("  ✓ Unbanned: " + parts[1]);
                break;
            }

            // ── Ban IP ────────────────────────────────────────────────
            case "ban-ip": {
                if (parts.length < 2) { System.out.println("  Usage: ban-ip <ip>"); break; }
                admin.banIp(parts[1]);
                System.out.println("  ✓ Banned IP: " + parts[1]);
                break;
            }

            // ── Unban IP ──────────────────────────────────────────────
            case "unban-ip": {
                if (parts.length < 2) { System.out.println("  Usage: unban-ip <ip>"); break; }
                admin.unbanIp(parts[1]);
                System.out.println("  ✓ Unbanned IP: " + parts[1]);
                break;
            }

            // ── Banned list ───────────────────────────────────────────
            case "banned": {
                System.out.println("  Banned IDs : " + server.getPlayerManager().getBannedIds());
                System.out.println("  Banned IPs : " + server.getPlayerManager().getBannedIps());
                break;
            }

            // ── Create room ───────────────────────────────────────────
            case "create-room": {
                if (parts.length < 2) { System.out.println("  Usage: create-room <name>"); break; }
                Room r = admin.createRoom(parts[1]);
                System.out.printf("  ✓ Created room '%s' (id: %s)%n", r.getName(), r.getId());
                break;
            }

            // ── Close room ────────────────────────────────────────────
            case "close-room": {
                if (parts.length < 2) { System.out.println("  Usage: close-room <roomId>"); break; }
                Room r = admin.getRoom(parts[1]);
                if (r == null) { System.out.println("  Room not found: " + parts[1]); break; }
                admin.closeRoom(parts[1]);
                System.out.printf("  ✓ Closed room '%s'%n", r.getName());
                break;
            }

            // ── Room state ────────────────────────────────────────────
            case "room-state": {
                if (parts.length < 3) {
                    System.out.println("  Usage: room-state <roomId> <WAITING|STARTING|PLAYING|PAUSED|ENDED>");
                    break;
                }
                Room r = admin.getRoom(parts[1]);
                if (r == null) { System.out.println("  Room not found: " + parts[1]); break; }
                Room.RoomState state;
                try {
                    state = Room.RoomState.valueOf(parts[2].toUpperCase());
                } catch (IllegalArgumentException e) {
                    System.out.println("  Invalid state. Valid: WAITING, STARTING, PLAYING, PAUSED, ENDED");
                    break;
                }
                admin.changeRoomState(parts[1], state);
                System.out.printf("  ✓ Room '%s' state → %s%n", r.getName(), state);
                break;
            }

            // ── Broadcast ─────────────────────────────────────────────
            // broadcast [roomId|-] <TAG> [message]
            case "broadcast": {
                if (parts.length < 2) {
                    System.out.println("  Usage: broadcast [roomId|-] <TAG> [message]");
                    break;
                }
                // Detect if first param looks like a room id or "-" (all)
                String roomId  = null;
                String tag;
                String msg     = null;

                if (parts.length == 2) {
                    // broadcast TAG
                    tag = parts[1];
                } else {
                    // Is parts[1] a known room id or "-"?
                    boolean firstIsRoom = parts[1].equals("-") || admin.getRoom(parts[1]) != null;
                    if (firstIsRoom) {
                        roomId = parts[1].equals("-") ? null : parts[1];
                        tag    = parts[2];
                        msg    = parts.length >= 4 ? parts[3] : null;
                    } else {
                        tag = parts[1];
                        msg = parts.length >= 3 ? parts[2] : null;
                    }
                }

                org.json.JSONObject payload = msg != null
                        ? new org.json.JSONObject().put("message", msg)
                        : null;
                admin.broadcast(roomId, tag, payload);
                System.out.printf("  ✓ Broadcast '%s' → %s%n", tag,
                        roomId != null ? "room " + roomId : "ALL");
                break;
            }

            // ── Send to player ────────────────────────────────────────
            // send <playerId> <TAG> [message]
            case "send": {
                if (parts.length < 3) {
                    System.out.println("  Usage: send <playerId> <TAG> [message]");
                    break;
                }
                Player p = admin.getPlayer(parts[1]);
                if (p == null) { System.out.println("  Player not found: " + parts[1]); break; }
                org.json.JSONObject payload = parts.length >= 4
                        ? new org.json.JSONObject().put("message", parts[3])
                        : null;
                admin.sendTo(parts[1], parts[2], payload);
                System.out.printf("  ✓ Sent '%s' to %s%n", parts[2], p.getName());
                break;
            }

            // ── Token ─────────────────────────────────────────────────
            case "token":
                System.out.println("  Admin token: " + server.getConfig().adminToken);
                break;

            // ── Stop server ───────────────────────────────────────────
            case "stop":
            case "exit":
            case "quit":
                System.out.println("[Console] Stopping server…");
                server.stop();
                System.exit(0);
                break;

            default:
                System.out.println("  Unknown command: '" + cmd + "'. Type 'help' for a list.");
        }
    }

    // ═══════════════════════════════════════════════════════════════════
    //  PRINT HELPERS
    // ═══════════════════════════════════════════════════════════════════

    private static void printAppInfo(ServerConfig config) {
        System.out.println();
        System.out.println("╔══════════════════════════════════════════════════════╗");
        System.out.println("║                  socket_base server                 ║");
        System.out.println("╠══════════════════════════════════════════════════════╣");
        System.out.printf( "║  Transport  : %-38s║%n", config.transport);
        System.out.printf( "║  TCP port   : %-38s║%n", config.tcpPort);
        System.out.printf( "║  WS  port   : %-38s║%n", config.wsPort > 0 ? config.wsPort : "disabled");
        System.out.printf( "║  Admin token: %-38s║%n", config.adminToken);
        System.out.printf( "║  Reconnect  : %-38s║%n", config.reconnectTimeoutMs + " ms");
        System.out.printf( "║  Max players: %-38s║%n", config.maxPlayersPerRoom == 0 ? "unlimited" : config.maxPlayersPerRoom);
        System.out.printf( "║  Max rooms  : %-38s║%n", config.maxRooms == 0 ? "unlimited" : config.maxRooms);
        System.out.printf( "║  Persistence: %-38s║%n", config.persistPath != null ? config.persistPath : "disabled");
        System.out.println("╚══════════════════════════════════════════════════════╝");
        System.out.println();
    }

    private static void printRuntimeInfo() {
        ServerConfig cfg = server.getConfig();
        long uptimeSec   = server.getUptimeMs() / 1000;
        System.out.println();
        System.out.println("  ── Server Info ─────────────────────────────────────");
        System.out.println("  Transport   : " + cfg.transport);
        System.out.printf( "  TCP port    : %d%n",  cfg.tcpPort);
        System.out.printf( "  WS  port    : %s%n",  cfg.wsPort > 0 ? cfg.wsPort : "disabled");
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
        System.out.println();
        System.out.println("  ── Admin Console Commands ──────────────────────────────────────────");
        System.out.println("  help                               Show this help");
        System.out.println("  info                               Server info & uptime");
        System.out.println("  token                              Show admin token");
        System.out.println();
        System.out.println("  list-players  (players)            List all players");
        System.out.println("  list-rooms    (rooms)              List all rooms");
        System.out.println();
        System.out.println("  kick     <playerId> [reason]       Kick a player");
        System.out.println("  ban      <playerId> [reason]       Ban a player by ID");
        System.out.println("  unban    <displayName>             Unban by display name");
        System.out.println("  ban-ip   <ip>                      Ban an IP address");
        System.out.println("  unban-ip <ip>                      Unban an IP address");
        System.out.println("  banned                             Show all ban lists");
        System.out.println();
        System.out.println("  create-room  <name>               Create a new room");
        System.out.println("  close-room   <roomId>             Close a room");
        System.out.println("  room-state   <roomId> <state>     Set room state");
        System.out.println("               States: WAITING STARTING PLAYING PAUSED ENDED");
        System.out.println();
        System.out.println("  broadcast  [roomId|-] <TAG> [msg] Broadcast event (- = all)");
        System.out.println("  send       <playerId> <TAG> [msg] Send event to player");
        System.out.println();
        System.out.println("  stop  (exit / quit)               Stop the server");
        System.out.println();
    }

    // ═══════════════════════════════════════════════════════════════════
    //  CLI PARSING
    // ═══════════════════════════════════════════════════════════════════

    private static ServerConfig parseArgs(String[] args) {
        ServerConfig.Builder cfg = new ServerConfig.Builder();

        for (int i = 0; i < args.length; i++) {
            switch (args[i].toLowerCase()) {

                case "--tcp":
                    cfg.transport(TransportMode.TCP);
                    break;

                case "--ws":
                    cfg.transport(TransportMode.WS);
                    break;

                case "--both":
                    cfg.transport(TransportMode.BOTH);
                    break;

                case "--port":
                    cfg.port(intArg(args, i++, "--port"));
                    break;

                case "--ws-port":
                    cfg.wsPort(intArg(args, i++, "--ws-port"));
                    break;

                case "--admin-token":
                    cfg.adminToken(strArg(args, i++, "--admin-token"));
                    break;

                case "--reconnect-ms":
                    cfg.reconnectTimeoutMs(intArg(args, i++, "--reconnect-ms"));
                    break;

                case "--max-players":
                    cfg.maxPlayersPerRoom(intArg(args, i++, "--max-players"));
                    break;

                case "--max-rooms":
                    cfg.maxRooms(intArg(args, i++, "--max-rooms"));
                    break;

                case "--no-default-room":
                    cfg.autoCreateDefaultRoom(false);
                    break;

                case "--persist":
                    cfg.persistPath(strArg(args, i++, "--persist"));
                    break;

                // Handled before parseArgs — just skip silently
                case "--show-appinfo":
                case "--no-console":
                    break;

                default:
                    System.err.println("[Main] Unknown argument ignored: " + args[i]);
            }
        }

        return cfg.build();
    }

    // ── Arg helpers ───────────────────────────────────────────────────

    private static int intArg(String[] args, int flagIndex, String flag) {
        int valueIndex = flagIndex + 1;
        if (valueIndex >= args.length) {
            System.err.println("[Main] Missing value for " + flag);
            System.exit(1);
        }
        try {
            return Integer.parseInt(args[valueIndex]);
        } catch (NumberFormatException e) {
            System.err.println("[Main] " + flag + " requires an integer, got: " + args[valueIndex]);
            System.exit(1);
            return 0;
        }
    }

    private static String strArg(String[] args, int flagIndex, String flag) {
        int valueIndex = flagIndex + 1;
        if (valueIndex >= args.length || args[valueIndex].startsWith("--")) {
            System.err.println("[Main] Missing value for " + flag);
            System.exit(1);
        }
        return args[valueIndex];
    }

    private static boolean hasFlag(String[] args, String flag) {
        for (String a : args) if (a.equalsIgnoreCase(flag)) return true;
        return false;
    }

    // ── Help ──────────────────────────────────────────────────────────

    private static void printHelp() {
        System.out.println(
            "socket_base server\n" +
            "\n" +
            "Usage: java -jar socket_base.jar [options]\n" +
            "\n" +
            "Transport:\n" +
            "  --tcp                 TCP only\n" +
            "  --ws                  WebSocket only\n" +
            "  --both                TCP + WebSocket\n" +
            "\n" +
            "Network:\n" +
            "  --port      <n>       TCP listen port          (default: 9000)\n" +
            "  --ws-port   <n>       WebSocket port           (default: tcp+1)\n" +
            "\n" +
            "Players / Rooms:\n" +
            "  --reconnect-ms <n>    Reconnect window in ms   (default: 30000)\n" +
            "  --max-players  <n>    Max players per room     (default: 0 = unlimited)\n" +
            "  --max-rooms    <n>    Max rooms                (default: 0 = unlimited)\n" +
            "  --no-default-room     Skip creating \"default\" room on startup\n" +
            "\n" +
            "Admin:\n" +
            "  --admin-token <s>     Secret admin token       (default: random UUID)\n" +
            "  --show-appinfo        Print full config banner on startup\n" +
            "  --no-console          Disable interactive admin console\n" +
            "\n" +
            "Persistence:\n" +
            "  --persist <path>      Path to JSON save file   (default: disabled)\n" +
            "\n" +
            "  --help                Print this message\n" +
            "\n" +
            "Examples:\n" +
            "  java -jar socket_base.jar\n" +
            "  java -jar socket_base.jar --show-appinfo --admin-token mysecret\n" +
            "  java -jar socket_base.jar --both --port 9000 --wsport 9001 --reconnect-ms 10000\n" +
            "  java -jar socket_base.jar --ws --no-console  # headless / daemon mode\n"
        );
    }
}
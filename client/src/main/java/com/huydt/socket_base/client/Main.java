package com.huydt.socket_base.client;

import com.huydt.socket_base.client.core.ClientConfig;
import com.huydt.socket_base.client.core.SocketBaseClient;
import com.huydt.socket_base.client.event.ClientEventType;
import org.json.JSONArray;
import org.json.JSONObject;

import java.util.Scanner;

/**
 * Demo entry point for {@code socket_client_base}.
 *
 * <h3>Usage</h3>
 * <pre>
 * java -jar socket_client_base.jar [options]
 *
 * Transport (default: WebSocket):
 *   --url   &lt;url&gt;      Full URL — scheme sets transport automatically
 *                         ws://host[:port][/path]
 *                         wss://host[:port][/path]
 *                         tcp://host:port
 *                         tcp+ssl://host:port
 *                         udp://host:port
 *                         udp+ssl://host:port
 *   --tcp               Use raw TCP (requires --host and --port)
 *   --udp               Use raw UDP (requires --host and --port)
 *   --ssl               Enable SSL/TLS (works with ws/tcp/udp)
 *
 * Connection:
 *   --host  &lt;h&gt;        Server host     (default: localhost)
 *   --port  &lt;n&gt;        Port            (default: scheme-specific)
 *   --name  &lt;n&gt;        Player name     (default: Player)
 *   --room  &lt;roomId&gt;   Auto-join room on connect
 *   --token &lt;token&gt;    Reconnect token from a previous session
 *   --admin &lt;token&gt;    Admin token (auto-sends ADMIN_AUTH)
 *   --help
 * </pre>
 *
 * <h3>Examples</h3>
 * <pre>
 * # WebSocket — URL without port
 * java -jar client.jar --url wss://game.example.com/ws --name Alice
 *
 * # WebSocket — explicit host + port + SSL
 * java -jar client.jar --host game.example.com --port 443 --ssl --name Alice
 *
 * # TCP
 * java -jar client.jar --tcp --host game.example.com --port 9002 --name Alice
 *
 * # TCP + SSL
 * java -jar client.jar --tcp --ssl --host game.example.com --port 9002 --name Alice
 *
 * # UDP
 * java -jar client.jar --udp --host game.example.com --port 9003 --name Alice
 * </pre>
 *
 * <h3>Console commands</h3>
 * <pre>
 *   info / players / room-join / room-leave / custom
 *   admin-auth / rooms / room-add / room-remove / room-state
 *   kick / disconnect / bans / ban / unban / ban-ip / unban-ip
 *   broadcast / send / stats / quit
 * </pre>
 */
public class Main {

    private static volatile JSONObject lastAppSnapshot = null;

    public static void main(String[] args) throws Exception {
        if (hasFlag(args, "--help") || hasFlag(args, "-h")) {
            printHelp();
            return;
        }

        // ── Build config ──────────────────────────────────────────────
        ClientConfig.Builder cfgBuilder = new ClientConfig.Builder();

        String url = strArg(args, "--url", null);
        if (url != null) {
            // --url overrides everything
            cfgBuilder.url(url);
        } else {
            // Transport flags
            boolean isTcp = hasFlag(args, "--tcp");
            boolean isUdp = hasFlag(args, "--udp");
            boolean ssl   = hasFlag(args, "--ssl");

            if (isTcp && isUdp)
                die("Cannot use --tcp and --udp together.");

            if (isTcp) {
                cfgBuilder.protocol(ssl ? ClientConfig.Protocol.TCP_SSL : ClientConfig.Protocol.TCP);
            } else if (isUdp) {
                cfgBuilder.protocol(ssl ? ClientConfig.Protocol.UDP_SSL : ClientConfig.Protocol.UDP);
            } else {
                cfgBuilder.protocol(ssl ? ClientConfig.Protocol.WSS : ClientConfig.Protocol.WS);
            }

            String host = strArg(args, "--host", "localhost");
            cfgBuilder.host(host);

            String portStr = strArg(args, "--port", null);
            if (portStr != null) cfgBuilder.port(Integer.parseInt(portStr));
            // else port stays -1 → effectivePort() returns scheme default
        }

        ClientConfig config = cfgBuilder.build();

        // ── Other CLI args ─────────────────────────────────────────────
        String  name        = strArg(args, "--name",  "Player");
        String  room        = strArg(args, "--room",  null);
        String  adminToken  = strArg(args, "--admin", null);
        String  reconnToken = strArg(args, "--token", null);

        // ── Build client ──────────────────────────────────────────────
        SocketBaseClient client = new SocketBaseClient.Builder()
                .config(config)
                .name(name)
                .roomId(room)
                .adminToken(adminToken)
                .reconnectToken(reconnToken)
                .build();

        // ── Event listeners ───────────────────────────────────────────
        client
            .on(ClientEventType.CONNECTED, e -> {
                String addr = config.protocol.isWebSocket() ? config.wsUri() : config.tcpAddress();
                System.out.println("[Event] Connected [" + config.protocol + "] → " + addr);
            })

            .on(ClientEventType.DISCONNECTED, e ->
                System.out.println("[Event] Disconnected: " + e.getMessage()))

            .on(ClientEventType.WELCOME, e -> {
                System.out.println("[Event] WELCOME     — " + client.getSession());
                String tok = client.getSession().getToken();
                if (tok != null)
                    System.out.println("[Event] Token       — " + tok
                            + "  (dùng --token " + tok + " để reconnect)");
                JSONObject roomJ = e.getObject("room");
                if (roomJ != null)
                    System.out.println("[Event] Room        — " + roomJ.optString("name"));
            })

            .on(ClientEventType.RECONNECTED, e -> {
                System.out.println("[Event] RECONNECTED — " + client.getSession());
                JSONObject roomJ = e.getObject("room");
                if (roomJ != null)
                    System.out.println("[Event] Room        — " + roomJ.optString("name"));
            })

            .on(ClientEventType.APP_SNAPSHOT, e -> {
                lastAppSnapshot = e.getPayload();
                int p = countArray(e.getPayload(), "players");
                int r = countArray(e.getPayload(), "rooms");
                System.out.printf("[Event] APP_SNAPSHOT  — %d player(s), %d room(s)%n", p, r);
            })

            .on(ClientEventType.ROOM_SNAPSHOT, e -> {
                JSONObject r = e.getPayload().optJSONObject("room");
                if (r != null) {
                    int count = client.getSession().getCurrentRoom() != null
                            ? client.getSession().getCurrentRoom().getPlayerCount() : 0;
                    System.out.printf("[Event] ROOM_SNAPSHOT — %s  players=%d%n",
                            r.optString("name"), count);
                }
            })

            .on(ClientEventType.ROOM_INFO, e ->
                System.out.println("[Event] ROOM_INFO   — " + e.getPayload().optString("name")))

            .on(ClientEventType.ROOM_LIST, e -> {
                System.out.println("[Event] ROOM_LIST:");
                JSONArray rooms = e.getPayload().optJSONArray("rooms");
                if (rooms != null) {
                    for (int i = 0; i < rooms.length(); i++) {
                        JSONObject r = rooms.getJSONObject(i);
                        System.out.printf("         • %-20s id=%-12s state=%-10s players=%d%n",
                                r.optString("name"), r.optString("id"),
                                r.optString("state"), r.optInt("playerCount"));
                    }
                }
            })

            .on(ClientEventType.ROOM_STATE_CHANGED, e ->
                System.out.printf("[Event] ROOM_STATE  — room=%s  %s → %s%n",
                        e.getPayload().optString("roomId"),
                        e.getPayload().optString("oldState"),
                        e.getPayload().optString("newState")))

            .on(ClientEventType.PLAYER_JOINED, e -> {
                JSONObject p = e.getObject("player");
                System.out.println("[Event] + " + (p != null ? p.optString("name") : "?") + " joined room");
            })

            .on(ClientEventType.PLAYER_LEFT, e ->
                System.out.println("[Event] - " + e.getPayload().optString("playerId") + " left room"))

            .on(ClientEventType.PLAYER_RECONNECTED, e -> {
                JSONObject p = e.getObject("player");
                System.out.println("[Event] ↩ " + (p != null ? p.optString("name") : "?") + " reconnected");
            })

            .on(ClientEventType.PLAYER_UPDATE, e -> {
                JSONObject p = e.getObject("player");
                if (p != null)
                    System.out.printf("[Event] PLAYER_UPDATE — %s (%s)%n",
                            p.optString("name"), p.optString("id"));
            })

            .on(ClientEventType.ROOM_UPDATE, e -> {
                JSONObject r = e.getObject("room");
                if (r != null)
                    System.out.printf("[Event] ROOM_UPDATE   — %s  state=%s  players=%d%n",
                            r.optString("name"), r.optString("state"), r.optInt("playerCount"));
            })

            .on(ClientEventType.KICKED,  e -> System.out.println("[Event] KICKED: "  + e.getPayload().optString("reason")))
            .on(ClientEventType.BANNED,  e -> System.out.println("[Event] BANNED: "  + e.getPayload().optString("reason")))
            .on(ClientEventType.ADMIN_AUTH_OK, e -> System.out.println("[Event] ADMIN_AUTH_OK — admin access granted"))

            .on(ClientEventType.BAN_LIST, e -> {
                System.out.println("[Event] BAN_LIST:");
                System.out.println("         playerIds : " + e.getPayload().optJSONArray("playerIds"));
                System.out.println("         ips       : " + e.getPayload().optJSONArray("ips"));
            })

            .on(ClientEventType.STATS, e -> {
                JSONObject p = e.getPayload();
                System.out.printf("[Event] STATS — rooms=%d  players=%d  uptime=%ds%n",
                        p.optInt("rooms"), p.optInt("players"), p.optLong("uptimeSec"));
            })

            .on(ClientEventType.CUSTOM_MSG, e -> {
                JSONObject p = e.getPayload();
                System.out.printf("[Event] CUSTOM_MSG  — tag=%s  data=%s%n",
                        p.optString("tag"), p.optJSONObject("data"));
            })

            .on(ClientEventType.ERROR, e -> System.err.println("[Event] ERROR: " + e.getMessage()))

            .onError((ev, ex) ->
                System.err.println("[EventBus] threw on " + ev.getType() + ": " + ex.getMessage()));

        // ── Connect ───────────────────────────────────────────────────
        String displayAddr = config.protocol.isWebSocket() ? config.wsUri() : config.tcpAddress();
        System.out.println("[Main] Connecting [" + config.protocol + "] → " + displayAddr + " as '" + name + "'…");
        client.connect();

        // ── Interactive console ───────────────────────────────────────
        Scanner sc = new Scanner(System.in);
        while (true) {
            System.out.print("client> ");
            if (!sc.hasNextLine()) break;
            String line = sc.nextLine().trim();
            if (line.isEmpty()) continue;

            String[] parts = line.split("\\s+", 4);
            String   cmd   = parts[0].toLowerCase();

            try {
                handleCommand(cmd, parts, client);
            } catch (Exception e) {
                System.err.println("[Console] Error: " + e.getMessage());
            }
        }
    }

    // ═══════════════════════════════════════════════════════════════════
    //  CONSOLE COMMANDS  (unchanged from original)
    // ═══════════════════════════════════════════════════════════════════

    private static void handleCommand(String cmd, String[] parts, SocketBaseClient client) {
        switch (cmd) {

            case "help": printConsoleHelp(); break;

            case "info":
                System.out.println("  Session  : " + client.getSession());
                System.out.println("  Config   : " + client.getConfig());
                System.out.println("  Protocol : " + client.getConfig().protocol);
                System.out.println("  Connected: " + client.isConnected());
                break;

            case "players": {
                com.huydt.socket_base.client.model.RoomInfo room = client.getSession().getCurrentRoom();
                if (room != null) {
                    System.out.printf("  [room: %s]  %-24s %-16s %-10s%n", room.name, "ID", "NAME", "STATUS");
                    System.out.println("  " + "-".repeat(56));
                    for (com.huydt.socket_base.client.model.PlayerInfo p : room.getPlayers().values())
                        System.out.printf("             %-24s %-16s %-10s%n",
                                p.id, p.name, p.connected ? "online" : "ghost");
                } else if (lastAppSnapshot != null) {
                    printPlayerTable(lastAppSnapshot.optJSONArray("players"), "lobby");
                } else {
                    System.out.println("  No snapshot yet — waiting for server");
                }
                break;
            }

            case "room-join": {
                if (parts.length < 2) { System.out.println("  Usage: room-join <roomId> [password]"); break; }
                client.joinRoom(parts[1], parts.length >= 3 ? parts[2] : null);
                break;
            }

            case "room-leave": client.leaveRoom(); break;

            case "custom": {
                if (parts.length < 2) { System.out.println("  Usage: custom <tag> [message]"); break; }
                JSONObject data = parts.length >= 3 ? new JSONObject().put("message", parts[2]) : null;
                client.custom(parts[1], data);
                break;
            }

            case "admin-auth": {
                if (parts.length < 2) { System.out.println("  Usage: admin-auth <token>"); break; }
                client.adminAuth(parts[1]);
                break;
            }

            case "rooms": client.listRooms(); break;

            case "room-add": {
                if (parts.length < 2) { System.out.println("  Usage: room-add <name> [maxPlayers]"); break; }
                int max = parts.length >= 3 ? safeInt(parts[2], 0) : 0;
                client.createRoom(parts[1], max, null);
                break;
            }

            case "room-remove": {
                if (parts.length < 2) { System.out.println("  Usage: room-remove <roomId>"); break; }
                client.closeRoom(parts[1]);
                break;
            }

            case "room-state": {
                if (parts.length < 3) { System.out.println("  Usage: room-state <roomId> <state>"); break; }
                client.setRoomState(parts[1], parts[2].toUpperCase());
                break;
            }

            case "kick": {
                if (parts.length < 2) { System.out.println("  Usage: kick <playerId> [reason]"); break; }
                client.kick(parts[1], parts.length >= 3 ? parts[2] : "Kicked by admin");
                break;
            }

            case "disconnect": {
                if (parts.length < 2) { System.out.println("  Usage: disconnect <playerId>"); break; }
                client.kick(parts[1], "Disconnected by admin");
                break;
            }

            case "bans": client.getBanList(); break;

            case "ban": {
                if (parts.length < 2) { System.out.println("  Usage: ban <playerId> [reason]"); break; }
                client.ban(parts[1], parts.length >= 3 ? parts[2] : "Banned by admin");
                break;
            }

            case "unban": {
                if (parts.length < 2) { System.out.println("  Usage: unban <displayName>"); break; }
                client.unban(parts[1]);
                break;
            }

            case "ban-ip": {
                if (parts.length < 2) { System.out.println("  Usage: ban-ip <ip>"); break; }
                client.banIp(parts[1]);
                break;
            }

            case "unban-ip": {
                if (parts.length < 2) { System.out.println("  Usage: unban-ip <ip>"); break; }
                client.unbanIp(parts[1]);
                break;
            }

            case "broadcast": {
                if (parts.length < 2) { System.out.println("  Usage: broadcast [roomId|-] <tag> [msg]"); break; }
                String roomId, tag, msgStr = null;
                if (parts.length == 2) {
                    roomId = null; tag = parts[1];
                } else {
                    boolean isRoom = parts[1].equals("-") || parts[1].matches("[a-zA-Z0-9_\\-]+");
                    if (isRoom && parts.length >= 3) {
                        roomId = parts[1].equals("-") ? null : parts[1];
                        tag = parts[2];
                        msgStr = parts.length >= 4 ? parts[3] : null;
                    } else {
                        roomId = null; tag = parts[1];
                        msgStr = parts.length >= 3 ? parts[2] : null;
                    }
                }
                client.adminBroadcast(roomId, tag, msgStr != null ? new JSONObject().put("message", msgStr) : null);
                break;
            }

            case "send": {
                if (parts.length < 3) { System.out.println("  Usage: send <playerId> <tag> [msg]"); break; }
                JSONObject data = parts.length >= 4 ? new JSONObject().put("message", parts[3]) : null;
                client.adminSend(parts[1], parts[2], data);
                break;
            }

            case "stats": client.getStats(); break;

            case "quit":
            case "exit":
                client.disconnect();
                System.exit(0);
                break;

            default:
                System.out.println("  Unknown command '" + cmd + "'. Type 'help'.");
        }
    }

    // ═══════════════════════════════════════════════════════════════════
    //  HELPERS
    // ═══════════════════════════════════════════════════════════════════

    private static void printPlayerTable(JSONArray players, String source) {
        if (players == null || players.length() == 0) {
            System.out.println("  (no players in " + source + " snapshot)");
            return;
        }
        System.out.printf("  [%s]  %-24s %-14s %-20s %-10s%n", source, "ID", "NAME", "ROOM", "STATUS");
        System.out.println("  " + "-".repeat(74));
        for (int i = 0; i < players.length(); i++) {
            JSONObject p = players.getJSONObject(i);
            String room = p.optString("roomName", null);
            if (room == null || room.isEmpty()) room = p.optString("roomId", null);
            if (room == null || room.isEmpty()) room = "—";
            System.out.printf("         %-24s %-14s %-20s %-10s%n",
                    p.optString("id"), p.optString("name"),
                    room, p.optBoolean("connected") ? "online" : "ghost");
        }
    }

    private static int countArray(JSONObject obj, String key) {
        JSONArray arr = obj.optJSONArray(key);
        return arr != null ? arr.length() : 0;
    }

    private static void printConsoleHelp() {
        System.out.println();
        System.out.println("  ── Player ──────────────────────────────────────────────────────────");
        System.out.println("  info                               Session & connection info");
        System.out.println("  players                            List players (lobby or room)");
        System.out.println("  room-join   <roomId> [password]    Join a room");
        System.out.println("  room-leave                         Leave current room");
        System.out.println("  custom      <tag> [message]        Send custom event");
        System.out.println();
        System.out.println("  ── Admin ───────────────────────────────────────────────────────────");
        System.out.println("  admin-auth  <token>                Authenticate as admin");
        System.out.println("  rooms                              List all rooms");
        System.out.println("  room-add    <name> [max]           Create a room");
        System.out.println("  room-remove <roomId>               Close a room");
        System.out.println("  room-state  <roomId> <state>       Set room state");
        System.out.println("              States: WAITING STARTING PLAYING PAUSED ENDED");
        System.out.println("  kick        <playerId> [reason]    Kick a player");
        System.out.println("  disconnect  <playerId>             Disconnect a player");
        System.out.println("  bans                               Show all ban lists");
        System.out.println("  ban         <playerId> [reason]    Ban a player");
        System.out.println("  unban       <displayName>          Unban by display name");
        System.out.println("  ban-ip      <ip>                   Ban an IP address");
        System.out.println("  unban-ip    <ip>                   Unban an IP address");
        System.out.println("  broadcast   [roomId|-] <tag> [msg] Broadcast event (- = all)");
        System.out.println("  send        <playerId> <tag> [msg] Send to player");
        System.out.println("  stats                              Server statistics");
        System.out.println();
        System.out.println("  quit                               Disconnect and exit");
        System.out.println();
    }

    private static void printHelp() {
        System.out.println(
            "socket_client_base\n" +
            "\n" +
            "Usage: java -jar socket_client_base.jar [options]\n" +
            "\n" +
            "  Transport (default: WebSocket ws://):\n" +
            "    --url   <url>       Full URL — sets transport from scheme\n" +
            "                          ws://host[:port][/path]\n" +
            "                          wss://host[:port][/path]   (port optional: default 443)\n" +
            "                          tcp://host:port\n" +
            "                          tcp+ssl://host:port\n" +
            "                          udp://host:port\n" +
            "                          udp+ssl://host:port\n" +
            "    --tcp               Use raw TCP transport\n" +
            "    --udp               Use raw UDP transport\n" +
            "    --ssl               Enable SSL/TLS (ws→wss, tcp→tcp+ssl, udp→udp+ssl)\n" +
            "\n" +
            "  Connection:\n" +
            "    --host  <h>         Server host    (default: localhost)\n" +
            "    --port  <n>         Port           (default: scheme-specific)\n" +
            "    --name  <n>         Player name    (default: Player)\n" +
            "    --room  <roomId>    Auto-join room on connect\n" +
            "    --token <token>     Reconnect token from a previous session\n" +
            "    --admin <token>     Admin token (auto-sends ADMIN_AUTH)\n" +
            "    --help\n" +
            "\n" +
            "  Examples:\n" +
            "    java -jar client.jar --url wss://game.example.com/ws --name Alice\n" +
            "    java -jar client.jar --host localhost --port 9001 --name Alice\n" +
            "    java -jar client.jar --tcp --host localhost --port 9002 --name Alice\n" +
            "    java -jar client.jar --tcp --ssl --host game.example.com --port 9002\n" +
            "    java -jar client.jar --udp --host localhost --port 9003 --name Alice\n"
        );
    }

    private static String strArg(String[] args, String flag, String def) {
        for (int i = 0; i < args.length - 1; i++)
            if (args[i].equalsIgnoreCase(flag)) return args[i + 1];
        return def;
    }

    private static boolean hasFlag(String[] args, String flag) {
        for (String a : args) if (a.equalsIgnoreCase(flag)) return true;
        return false;
    }

    private static int safeInt(String s, int def) {
        try { return Integer.parseInt(s); } catch (NumberFormatException e) { return def; }
    }

    private static void die(String msg) {
        System.err.println("[Error] " + msg);
        System.exit(1);
    }
}

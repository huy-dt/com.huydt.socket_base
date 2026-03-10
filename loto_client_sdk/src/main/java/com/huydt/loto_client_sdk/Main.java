package com.huydt.loto_client_sdk;

import com.huydt.loto_client_sdk.client.LotoClient;
import com.huydt.loto_client_sdk.model.LotoPlayerInfo;
import com.huydt.loto_client_sdk.model.LotoRoomInfo;
import com.huydt.socket_base.client.core.ClientConfig;
import com.huydt.socket_base.client.core.ClientSession;
import com.huydt.socket_base.client.core.SocketBaseClient;
import com.huydt.socket_base.client.event.ClientEventType;
import com.huydt.socket_base.client.model.PlayerInfo;
import org.json.JSONArray;
import org.json.JSONObject;

import java.io.*;
import java.util.Scanner;
import java.util.prefs.Preferences;

/**
 * Interactive CLI client for the Loto server.
 *
 * Usage:
 *   java -jar loto_client_sdk.jar --name Alice [options]
 *
 * Options:
 *   --host        <host>   Server host              (default: localhost)
 *   --port        <n>      Server port              (default: 9001)
 *   --name        <s>      Player name              (required)
 *   --room        <id>     Auto-join room on connect
 *   --token       <s>      Reconnect token
 *   --admin-token <s>      Admin token
 *   --ssl                  Use WSS
 *   --tcp                  Use TCP
 *   --url         <url>    Full URL
 *   --no-save             Don't save/restore token
 *
 * Interactive commands:
 *   join   <roomId>           Join a room
 *   leave                     Leave current room
 *   buy                       Buy a page
 *   start                     Start the game
 *   claim                     Claim win (bingo!)
 *   reset                     Reset room (admin)
 *   info                      Show current room & self info
 *   numbers                   Show drawn numbers
 *   players                   List players in room
 *   kick   <playerId> [r]     Kick player (admin)
 *   ban    <playerId> [r]     Ban player (admin)
 *   rooms                     List all rooms
 *   disconnect                Disconnect from server
 *   reconnect                 Reconnect using saved token
 *   help                      Show this help
 */
public class Main {

    private static LotoClient client;
    private static String     playerName;
    private static boolean    saveToken = true;

    // Prefs key for token persistence
    private static final String PREF_TOKEN = "loto_client_token";

    public static void main(String[] args) throws Exception {
        // ── Parse CLI args ────────────────────────────────────────────────
        String  host       = "localhost";
        int     port       = 9001;
        String  name       = null;
        String  roomId     = null;
        String  token      = null;
        String  adminToken = null;
        String  url        = null;
        boolean ssl        = false;
        boolean tcp        = false;

        for (int i = 0; i < args.length; i++) {
            switch (args[i]) {
                case "--host":        host       = args[++i];                    break;
                case "--port":        port       = Integer.parseInt(args[++i]);  break;
                case "--name":        name       = args[++i];                    break;
                case "--room":        roomId     = args[++i];                    break;
                case "--token":       token      = args[++i];                    break;
                case "--admin-token": adminToken = args[++i];                    break;
                case "--url":         url        = args[++i];                    break;
                case "--ssl":         ssl        = true;                         break;
                case "--tcp":         tcp        = true;                         break;
                case "--no-save":     saveToken  = false;                        break;
                default: System.err.println("[WARN] Unknown: " + args[i]);
            }
        }

        if (name == null) {
            System.err.println("[ERROR] --name is required");
            System.exit(1);
        }
        playerName = name;

        // ── Restore saved token ───────────────────────────────────────────
        if (token == null && saveToken) {
            Preferences prefs = Preferences.userRoot().node("loto_client_" + name);
            token = prefs.get(PREF_TOKEN, null);
            if (token != null)
                System.out.println("[INFO] Restored saved token — will attempt reconnect");
        }

        // ── Build client ──────────────────────────────────────────────────
        SocketBaseClient.Builder builder = new SocketBaseClient.Builder().name(name);

        if (url != null) {
            builder.url(url);
        } else {
            builder.host(host).port(port);
            if (tcp) builder.protocol(ssl ? ClientConfig.Protocol.TCP_SSL
                                          : ClientConfig.Protocol.TCP);
            else if (ssl) builder.useSsl(true);
        }

        if (roomId     != null) builder.roomId(roomId);
        if (token      != null) builder.reconnectToken(token);
        if (adminToken != null) builder.adminToken(adminToken);

        final String finalHost = host;
        final int    finalPort = port;
        final boolean finalSsl = ssl;
        final boolean finalTcp = tcp;

        client = new LotoClient(builder) {
            @Override
            protected void onRoundStart(JSONObject data) {
                System.out.println("\n╔═══════════════════════════════╗");
                System.out.printf ("║  ROUND %d STARTED!             %n", data.optInt("round"));
                System.out.println("║  Numbers will be drawn every  ║");
                System.out.println("║  3 seconds. Type 'claim' to   ║");
                System.out.println("║  claim bingo when you win!    ║");
                System.out.println("╚═══════════════════════════════╝");
                prompt();
            }

            @Override
            protected void onNumberDrawn(JSONObject data) {
                int number    = data.optInt("number");
                int remaining = data.optInt("remaining");
                JSONArray arr = data.optJSONArray("drawnNumbers");
                System.out.println("\n  🎱 Number drawn: " + number
                        + "  (" + remaining + " remaining)");
                if (arr != null && arr.length() <= 10) {
                    System.out.println("  Drawn so far: " + arr);
                }
                prompt();
            }

            @Override
            protected void onGameOver(JSONObject data) {
                String winnerId   = data.optString("winnerId", "");
                String winnerName = data.optString("winnerName", "");
                long   jackpot    = data.optLong("jackpot", 0);
                String myId       = getSession().getPlayerId();

                System.out.println("\n╔═══════════════════════════════╗");
                if (!winnerId.isEmpty()) {
                    System.out.println("║           GAME OVER!          ║");
                    System.out.printf ("║  Winner : %-20s║%n", winnerName);
                    System.out.printf ("║  Jackpot: %-20d║%n", jackpot);
                    if (myId != null && myId.equals(winnerId)) {
                        System.out.println("║  🎉 YOU WON! Congratulations! ║");
                    }
                } else {
                    System.out.println("║  No winner — all numbers drew ║");
                }
                System.out.println("╚═══════════════════════════════╝");
                prompt();
            }

            @Override
            protected void onRoundReset(JSONObject data) {
                System.out.println("\n[RESET] Room reset to WAITING. Buy pages for next round!");
                prompt();
            }

            @Override
            protected void onJackpotUpdate(JSONObject data) {
                System.out.println("  💰 Jackpot: " + data.optLong("jackpot"));
                prompt();
            }

            @Override
            protected void onAutoStartScheduled(JSONObject data) {
                System.out.println("  ⏳ Game auto-starts in "
                        + data.optLong("startsInMs") + "ms");
                prompt();
            }
        };

        // ── Register events ───────────────────────────────────────────────
        client.on(ClientEventType.CONNECTED, e ->
                System.out.println("[CONNECTED] to server"));

        client.on(ClientEventType.WELCOME, e -> {
            ClientSession s = client.getSession();
            System.out.println("[WELCOME] You are: " + s.getName()
                    + " (id=" + s.getPlayerId() + ")");
            saveToken(s.getName(), s.getToken());
            System.out.println("  Token saved. Type 'help' for commands.");
            prompt();
        });

        client.on(ClientEventType.RECONNECTED, e -> {
            ClientSession s = client.getSession();
            System.out.println("[RECONNECTED] Session restored: " + s.getName()
                    + " room=" + s.getRoomId());
            prompt();
        });

        client.on(ClientEventType.DISCONNECTED, e -> {
            System.out.println("[DISCONNECTED] " + e.getPayload().optString("message")
                    + "\n  Type 'reconnect' to reconnect.");
            prompt();
        });

        client.on(ClientEventType.ROOM_SNAPSHOT, e -> {
            LotoRoomInfo room = client.getCurrentLotoRoom();
            if (room == null) return;
            System.out.println("\n[ROOM] " + room.getName()
                    + " state=" + room.getState()
                    + " jackpot=" + room.jackpot
                    + " price=" + room.pricePerPage);
            LotoPlayerInfo self = client.getSelfInfo();
            if (self != null)
                System.out.println("  You: pages=" + self.pageCount);
            prompt();
        });

        client.on(ClientEventType.APP_SNAPSHOT, e -> {
            JSONArray rooms = e.getPayload().optJSONArray("rooms");
            if (rooms != null && rooms.length() > 0) {
                System.out.println("\n[LOBBY] " + rooms.length() + " room(s) available:");
                for (int i = 0; i < rooms.length(); i++) {
                    JSONObject r = rooms.getJSONObject(i);
                    System.out.printf("  [%s] %-12s state=%-10s players=%d%n",
                            r.optString("id"), r.optString("name"),
                            r.optString("state"), r.optInt("playerCount"));
                }
            }
            prompt();
        });

        client.on(ClientEventType.PLAYER_JOINED, e -> {
            JSONObject p = e.getPayload().optJSONObject("player");
            if (p != null) System.out.println("  [+] " + p.optString("name") + " joined");
            prompt();
        });

        client.on(ClientEventType.PLAYER_LEFT, e -> {
            boolean perm = e.getPayload().optBoolean("permanent", false);
            System.out.println("  [-] Player " + e.getPayload().optString("playerId")
                    + (perm ? " left" : " disconnected (ghost)"));
            prompt();
        });

        client.on(ClientEventType.PLAYER_RECONNECTED, e -> {
            JSONObject p = e.getPayload().optJSONObject("player");
            if (p != null) System.out.println("  [~] " + p.optString("name") + " reconnected");
            prompt();
        });

        client.on(ClientEventType.PLAYER_UPDATE, e -> {
            JSONObject p = e.getPayload().optJSONObject("player");
            if (p == null) return;
            String myId = client.getSession().getPlayerId();
            if (p.optString("id").equals(myId)) {
                System.out.println("  [YOU] pages=" + p.optInt("pageCount"));
            }
            prompt();
        });

        client.on(ClientEventType.ROOM_STATE_CHANGED, e -> {
            System.out.println("  [STATE] " + e.getPayload().optString("oldState")
                    + " → " + e.getPayload().optString("newState"));
            prompt();
        });

        client.on(ClientEventType.KICKED, e ->
                System.out.println("[KICKED] " + e.getPayload().optString("reason")));

        client.on(ClientEventType.BANNED, e ->
                System.out.println("[BANNED] " + e.getPayload().optString("reason")));

        client.on(ClientEventType.ERROR, e ->
                System.err.println("[ERROR] " + e.getPayload().optString("code")
                        + ": " + e.getPayload().optString("detail")));

        // ── Print banner ──────────────────────────────────────────────────
        System.out.println("╔══════════════════════════════════╗");
        System.out.println("║       Loto Client SDK            ║");
        System.out.println("╠══════════════════════════════════╣");
        System.out.printf ("║  Name   : %-22s║%n", name);
        if (url != null)
            System.out.printf("║  URL    : %-22s║%n", url);
        else
            System.out.printf("║  Server : %-22s║%n",
                    host + ":" + port + (ssl ? "(SSL)" : "") + (tcp ? "(TCP)" : ""));
        System.out.println("╚══════════════════════════════════╝");
        System.out.println("Connecting...");

        // ── Connect in background ─────────────────────────────────────────
        Thread connectThread = new Thread(client::connect);
        connectThread.setDaemon(true);
        connectThread.start();

        Runtime.getRuntime().addShutdownHook(new Thread(() -> {
            System.out.println("\n[INFO] Disconnecting...");
            client.disconnect();
        }));

        // ── Interactive command loop ───────────────────────────────────────
        Scanner scanner = new Scanner(System.in);
        while (scanner.hasNextLine()) {
            String line = scanner.nextLine().trim();
            if (line.isEmpty()) continue;
            String[] parts = line.split("\\s+", 3);
            String cmd = parts[0].toLowerCase();

            try {
                switch (cmd) {
                    case "join": {
                        if (parts.length < 2) { System.out.println("Usage: join <roomId>"); break; }
                        client.joinRoom(parts[1]);
                        System.out.println("[CMD] Joining room: " + parts[1]);
                        break;
                    }
                    case "leave": {
                        client.leaveRoom();
                        System.out.println("[CMD] Left room");
                        break;
                    }
                    case "buy": {
                        boolean ok = client.buyPage();
                        System.out.println("[CMD] BUY_PAGE sent: " + (ok ? "OK" : "not connected"));
                        break;
                    }
                    case "start": {
                        boolean ok = client.startGame();
                        System.out.println("[CMD] START_GAME sent: " + (ok ? "OK" : "not connected"));
                        break;
                    }
                    case "claim": {
                        boolean ok = client.claimWin();
                        System.out.println("[CMD] CLAIM_WIN sent: " + (ok ? "OK" : "not connected"));
                        break;
                    }
                    case "reset": {
                        boolean ok = client.resetRoom();
                        System.out.println("[CMD] RESET_ROOM sent: " + (ok ? "OK" : "not connected"));
                        break;
                    }
                    case "info": {
                        LotoRoomInfo room = client.getCurrentLotoRoom();
                        if (room == null) { System.out.println("  Not in a room"); break; }
                        System.out.println("── Room ──────────────────────────");
                        System.out.println("  ID       : " + room.getId());
                        System.out.println("  Name     : " + room.getName());
                        System.out.println("  State    : " + room.getState());
                        System.out.println("  Jackpot  : " + room.jackpot);
                        System.out.println("  Price    : " + room.pricePerPage);
                        System.out.println("  Round    : " + room.round);
                        System.out.println("  Players  : " + room.getPlayers().size());
                        LotoPlayerInfo self = client.getSelfInfo();
                        if (self != null) {
                            System.out.println("── You ───────────────────────────");
                            System.out.println("  Name     : " + self.name);
                            System.out.println("  Money    : " + self.money);
                            System.out.println("  Pages    : " + self.pageCount);
                        }
                        System.out.println("──────────────────────────────────");
                        break;
                    }
                    case "numbers": {
                        LotoRoomInfo room = client.getCurrentLotoRoom();
                        if (room == null) { System.out.println("  Not in a room"); break; }
                        System.out.println("  Drawn: " + room.drawnNumbers
                                + " (" + room.drawnNumbers.size() + " total)");
                        break;
                    }
                    case "players": {
                        LotoRoomInfo room = client.getCurrentLotoRoom();
                        if (room == null) { System.out.println("  Not in a room"); break; }
                        System.out.println("── Players (" + room.getPlayers().size() + ") ──");
                        for (PlayerInfo pi : room.getPlayers().values()) {
                            LotoPlayerInfo lp = (LotoPlayerInfo) pi;
                            System.out.printf("  [%s] %-12s pages=%-3d connected=%b%n",
                                    lp.id, lp.name, lp.pageCount, lp.connected);
                        }
                        break;
                    }
                    case "kick": {
                        if (parts.length < 2) { System.out.println("Usage: kick <playerId> [reason]"); break; }
                        String reason = parts.length > 2 ? parts[2] : "Kicked";
                        client.kick(parts[1], reason);
                        System.out.println("[CMD] Kicked: " + parts[1]);
                        break;
                    }
                    case "ban": {
                        if (parts.length < 2) { System.out.println("Usage: ban <playerId> [reason]"); break; }
                        String reason = parts.length > 2 ? parts[2] : "Banned";
                        client.ban(parts[1], reason);
                        System.out.println("[CMD] Banned: " + parts[1]);
                        break;
                    }
                    case "rooms": {
                        client.listRooms();
                        System.out.println("[CMD] Room list requested...");
                        break;
                    }
                    case "disconnect": {
                        client.disconnect();
                        System.out.println("[CMD] Disconnected");
                        break;
                    }
                    case "reconnect": {
                        System.out.println("[CMD] Reconnecting...");
                        connectThread = new Thread(client::connect);
                        connectThread.setDaemon(true);
                        connectThread.start();
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

    private static void saveToken(String name, String token) {
        if (!saveToken || token == null) return;
        try {
            Preferences prefs = Preferences.userRoot().node("loto_client_" + name);
            prefs.put(PREF_TOKEN, token);
            prefs.flush();
        } catch (Exception ignored) {}
    }

    private static void prompt() {
        System.out.print("> ");
    }

    private static void printHelp() {
        System.out.println("── Commands ─────────────────────────────────────");
        System.out.println("  join   <roomId>           Join a room");
        System.out.println("  leave                     Leave current room");
        System.out.println("  buy                       Buy a page/ticket");
        System.out.println("  start                     Start the game");
        System.out.println("  claim                     Claim bingo win!");
        System.out.println("  reset                     Reset room (admin)");
        System.out.println("  info                      Room & self info");
        System.out.println("  numbers                   Show drawn numbers");
        System.out.println("  players                   List room players");
        System.out.println("  kick   <playerId> [r]     Kick player (admin)");
        System.out.println("  ban    <playerId> [r]     Ban player (admin)");
        System.out.println("  rooms                     List all rooms");
        System.out.println("  disconnect                Disconnect");
        System.out.println("  reconnect                 Reconnect");
        System.out.println("─────────────────────────────────────────────────");
    }
}

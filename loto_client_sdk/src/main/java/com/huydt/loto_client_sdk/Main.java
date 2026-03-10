package com.huydt.loto_client_sdk;

import com.huydt.loto_client_sdk.client.LotoClient;
import com.huydt.loto_client_sdk.model.LotoPlayerInfo;
import com.huydt.loto_client_sdk.model.LotoRoomInfo;
import com.huydt.socket_base.client.core.ClientConfig;
import com.huydt.socket_base.client.core.ClientSession;
import com.huydt.socket_base.client.core.SocketBaseClient;
import com.huydt.socket_base.client.event.ClientEventType;
import com.huydt.socket_base.client.model.PlayerInfo;
import org.json.JSONObject;

/**
 * CLI entry point — connect to a Loto server and print events.
 *
 * Usage:
 *   java -jar loto_client_sdk.jar [options]
 *
 * Options:
 *   --host        <host>   Server host              (default: localhost)
 *   --port        <n>      Server port              (default: 9001)
 *   --name        <s>      Player display name      (required)
 *   --room        <id>     Auto-join room after connect
 *   --token       <s>      Reconnect token from previous session
 *   --admin-token <s>      Admin token
 *   --ssl                  Use WSS instead of WS
 *   --tcp                  Use TCP transport
 *   --url         <url>    Full URL (overrides host/port/protocol)
 */
public class Main {

    public static void main(String[] args) throws InterruptedException {
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
                default:
                    System.err.println("[WARN] Unknown argument: " + args[i]);
            }
        }

        if (name == null) {
            System.err.println("[ERROR] --name is required");
            System.exit(1);
        }

        // ── Build client ──────────────────────────────────────────────────
        SocketBaseClient.Builder builder = new SocketBaseClient.Builder().name(name);

        if (url != null) {
            builder.url(url);
        } else {
            builder.host(host).port(port);
            if (tcp) builder.protocol(ssl ? ClientConfig.Protocol.TCP_SSL : ClientConfig.Protocol.TCP);
            else if (ssl) builder.useSsl(true);
        }

        if (roomId     != null) builder.roomId(roomId);
        if (token      != null) builder.reconnectToken(token);
        if (adminToken != null) builder.adminToken(adminToken);

        LotoClient client = new LotoClient(builder) {
            @Override protected void onRoundStart(JSONObject d)    { System.out.println("[ROUND_START]    " + d); }
            @Override protected void onNumberDrawn(JSONObject d)   { System.out.println("[NUMBER_DRAWN]   " + d); }
            @Override protected void onRoundEnd(JSONObject d)      { System.out.println("[ROUND_END]      " + d); }
            @Override protected void onGameOver(JSONObject d)      { System.out.println("[GAME_OVER]      " + d); }
            @Override protected void onJackpotUpdate(JSONObject d) { System.out.println("[JACKPOT_UPDATE] " + d); }
        };

        // ── Register events ───────────────────────────────────────────────
        client.on(ClientEventType.CONNECTED, e ->
                System.out.println("[CONNECTED]"));

        client.on(ClientEventType.WELCOME, e -> {
            ClientSession s = client.getSession();
            System.out.println("[WELCOME] id=" + s.getPlayerId() + " token=" + s.getToken());
            System.out.println("          Save token: --token " + s.getToken());
        });

        client.on(ClientEventType.RECONNECTED, e ->
                System.out.println("[RECONNECTED] session restored"));

        client.on(ClientEventType.DISCONNECTED, e ->
                System.out.println("[DISCONNECTED] " + e.getPayload().optString("message")));

        client.on(ClientEventType.ROOM_SNAPSHOT, e -> {
            LotoRoomInfo room = (LotoRoomInfo) client.getSession().getCurrentRoom();
            if (room == null) return;
            System.out.println("[ROOM_SNAPSHOT] room=" + room.getId()
                    + " jackpot=" + room.jackpot
                    + " price=" + room.pricePerPage
                    + " players=" + room.getPlayers().size());
            for (PlayerInfo pi : room.getPlayers().values()) {
                LotoPlayerInfo lp = (LotoPlayerInfo) pi;
                System.out.println("  player=" + lp.name + " pages=" + lp.pageCount);
            }
        });

        client.on(ClientEventType.PLAYER_JOINED, e ->
                System.out.println("[PLAYER_JOINED] " + e.getPayload().optJSONObject("player")));

        client.on(ClientEventType.PLAYER_LEFT, e -> {
            boolean permanent = e.getPayload().optBoolean("permanent", false);
            System.out.println("[PLAYER_LEFT] id=" + e.getPayload().optString("playerId")
                    + " permanent=" + permanent);
        });

        client.on(ClientEventType.PLAYER_UPDATE, e ->
                System.out.println("[PLAYER_UPDATE] " + e.getPayload().optJSONObject("player")));

        client.on(ClientEventType.ROOM_STATE_CHANGED, e ->
                System.out.println("[STATE_CHANGED] " + e.getPayload().optString("oldState")
                        + " → " + e.getPayload().optString("newState")));

        client.on(ClientEventType.ERROR, e ->
                System.err.println("[ERROR] " + e.getPayload()));

        // ── Print startup info ────────────────────────────────────────────
        System.out.println("====================================");
        System.out.println("  Loto Client SDK");
        System.out.println("====================================");
        System.out.println("  Name   : " + name);
        if (url != null) System.out.println("  URL    : " + url);
        else             System.out.println("  Server : " + host + ":" + port
                                 + (ssl ? " (SSL)" : "") + (tcp ? " (TCP)" : ""));
        if (roomId != null) System.out.println("  Room   : " + roomId);
        System.out.println("====================================");

        // ── Connect & block ───────────────────────────────────────────────
        Runtime.getRuntime().addShutdownHook(new Thread(() -> {
            System.out.println("\n[INFO] Disconnecting...");
            client.disconnect();
        }));

        client.connect();

        // Keep main thread alive
        Thread.currentThread().join();
    }
}

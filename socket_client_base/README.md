# socket_client_base

WebSocket client SDK for [`socket_base`](../socket_base) servers.

## Quick start

```bash
mvn package
java -jar target/socket_client_base-1.0.0.jar --host localhost --port 9001 --name Alice
```

### Admin mode
```bash
java -jar target/socket_client_base-1.0.0.jar \
    --host localhost --port 9001 \
    --name Admin \
    --admin mysecrettoken
```

---

## CLI options

| Flag | Default | Description |
|---|---|---|
| `--host <h>` | `localhost` | Server host |
| `--port <n>` | `9001` | WebSocket port |
| `--name <n>` | `Player` | Player display name |
| `--room <id>` | — | Auto-join this room on connect |
| `--admin <token>` | — | Send `ADMIN_AUTH` automatically |
| `--ssl` | — | Use `WSS` |
| `--no-reconnect` | — | Disable auto-reconnect |

---

## Console commands

### Player
| Command | Description |
|---|---|
| `info` | Show session & connection info |
| `join-room <roomId> [pass]` | Join a room |
| `leave-room` | Leave current room |
| `custom <tag> [message]` | Send custom event |

### Admin (requires `--admin` or `admin-auth`)
| Command | Description |
|---|---|
| `admin-auth <token>` | Authenticate as admin |
| `list-rooms` | List all rooms |
| `list-players` | Show players from last snapshot |
| `kick <playerId> [reason]` | Kick a player |
| `ban <playerId> [reason]` | Ban a player |
| `unban <name>` | Unban by display name |
| `ban-ip <ip>` / `unban-ip <ip>` | IP ban management |
| `ban-list` | Show all ban lists |
| `create-room <name> [max]` | Create a room |
| `close-room <roomId>` | Close a room |
| `room-state <roomId> <state>` | Change room state |
| `broadcast [roomId\|-] <tag> [msg]` | Broadcast event |
| `send <playerId> <tag> [msg]` | Send to one player |
| `stats` | Server statistics |
| `quit` | Disconnect and exit |

---

## Embed as a library

```java
SocketBaseClient client = new SocketBaseClient.Builder()
    .host("localhost")
    .port(9001)
    .name("Alice")
    .build();

client.on(ClientEventType.WELCOME, e -> {
    System.out.println("My id: " + e.getPlayerId());
    System.out.println("Token: " + e.getToken()); // save for reconnect
});

client.on(ClientEventType.APP_SNAPSHOT, e -> {
    JSONArray rooms = e.getPayload().getJSONArray("rooms");
    // render lobby
});

client.on(ClientEventType.CUSTOM_MSG, e -> {
    String tag  = e.getPayload().optString("tag");
    JSONObject data = e.getPayload().optJSONObject("data");
    // handle game events
});

client.connect();
```

### Admin API
```java
SocketBaseClient admin = new SocketBaseClient.Builder()
    .host("localhost").port(9001)
    .name("AdminBot")
    .adminToken("mysecret")       // auto-sends ADMIN_AUTH after connect
    .build();

admin.on(ClientEventType.ADMIN_AUTH_OK, e -> {
    admin.listRooms();
    admin.kick("badPlayerId", "cheating");
    admin.createRoom("Arena", 10, null);
    admin.setRoomState("roomId", "PLAYING");
    admin.getStats();
});

admin.connect();
```

### Extend for custom messages
```java
public class MyClient extends SocketBaseClient {
    public MyClient(Builder b) { super(b); }

    @Override
    protected void dispatchCustom(InboundMsg msg, JSONObject payload) {
        String tag = payload.optString("tag");
        switch (tag) {
            case "ROUND_START": onRoundStart(payload.optJSONObject("data")); break;
            case "GAME_OVER":   onGameOver(payload.optJSONObject("data"));   break;
            default: super.dispatchCustom(msg, payload);
        }
    }
}
```

---

## Project structure

```
socket_client_base/
├── pom.xml
└── src/main/java/com/huydt/socket_client_base/
    ├── Main.java                        ← CLI entry point
    ├── core/
    │   ├── ClientConfig.java            ← Connection config
    │   ├── ClientSession.java           ← Holds playerId / token / roomId
    │   └── SocketBaseClient.java        ← Main client class
    ├── event/
    │   ├── ClientEventType.java         ← All event types
    │   ├── ClientEvent.java             ← Event payload object
    │   ├── ClientEventListener.java     ← Functional interface
    │   └── ClientEventBus.java          ← Dispatcher
    └── protocol/
        ├── MsgType.java                 ← Shared message types
        ├── OutboundMsg.java             ← Client → Server builders
        └── InboundMsg.java              ← Server → Client parser
```

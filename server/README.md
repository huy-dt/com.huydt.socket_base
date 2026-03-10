# socket_base SDK
### `com.huydt.socket_base`

Generic, extensible socket server foundation for multiplayer game backends.  
Built on the patterns from `com.loto` but rewritten as a clean, game-agnostic base layer.

---

## Architecture

```
SocketBaseServer          ← entry point (open/close server, CLI args)
├── ServerConfig          ← immutable config (port, WS, admin token, …)
├── PlayerManager         ← connect / reconnect / disconnect / kick / ban
├── RoomManager           ← create / join / leave / close rooms
├── MessageDispatcher     ← route inbound messages (extend for game logic)
├── AdminService          ← programmatic admin API
└── EventBus              ← pub/sub for all server events
```

### Model hierarchy
```
BaseModel          ← id, createdAt, toJson/fromJson, equals/hashCode
├── Player         ← token, connId, roomId, connected, metadata
└── Room           ← state, players, maxPlayers, metadata
```

---

## Quick Start

### 1. Minimal server
```java
SocketBaseServer server = new SocketBaseServer.Builder().build();
new Thread(server::startSafe).start();
```

### 2. Fully configured
```java
ServerConfig config = new ServerConfig.Builder()
    .port(9000)
    .wsPort(9001)
    .transport(TransportMode.BOTH)   // --tcp | --ws | --both
    .adminToken("my-secret")
    .reconnectTimeoutMs(30_000)
    .maxPlayersPerRoom(50)
    .autoCreateDefaultRoom(true)
    .build();

SocketBaseServer server = new SocketBaseServer.Builder()
    .config(config)
    .build();

server.startSafe();
```

### 3. From CLI args
```java
// Supports: --tcp | --ws | --both | --port N | --wsport N | --admin-token S
SocketBaseServer server = SocketBaseServer.fromArgs(args);
server.startSafe();
```

---

## Events

Subscribe via `server.getEventBus()`:

```java
server.getEventBus()
    .on(EventType.PLAYER_JOINED,      e -> log("Welcome: " + e.getPlayer().getName()))
    .on(EventType.PLAYER_LEFT,        e -> log("Goodbye: " + e.getPlayer().getName()))
    .on(EventType.PLAYER_RECONNECTED, e -> log("Back: " + e.getPlayer().getName()))
    .on(EventType.ROOM_STATE_CHANGED, e -> log("State: " + e.getRoom().getState()))
    .on(EventType.ADMIN_AUTH,         e -> log("Admin in: " + e.getMessage()))
    .on(EventType.CUSTOM,             e -> handleCustom(e.getTag(), e))
    .onAny(e -> metrics.record(e));
```

### Built-in EventTypes
| Category | Events |
|---|---|
| Server | `SERVER_STARTED`, `SERVER_STOPPED` |
| Player | `PLAYER_JOINED`, `PLAYER_RECONNECTED`, `PLAYER_DISCONNECTED`, `PLAYER_LEFT`, `PLAYER_KICKED`, `PLAYER_BANNED`, `PLAYER_UNBANNED` |
| Room | `ROOM_CREATED`, `ROOM_CLOSED`, `ROOM_STATE_CHANGED`, `ROOM_PLAYER_JOINED`, `ROOM_PLAYER_LEFT` |
| Messaging | `MESSAGE_RECEIVED`, `MESSAGE_SENT`, `BROADCAST` |
| Admin | `ADMIN_AUTH`, `ADMIN_COMMAND` |
| Misc | `ERROR`, `CUSTOM` |

---

## Admin API

### Over the wire
```json
// Authenticate
{ "type": "ADMIN_AUTH", "payload": { "token": "my-secret" } }
→ { "type": "ADMIN_AUTH_OK" }

// Then use admin commands:
{ "type": "KICK",       "payload": { "playerId": "abc", "reason": "AFK" } }
{ "type": "BAN",        "payload": { "playerId": "xyz", "reason": "Cheating" } }
{ "type": "BAN_IP",     "payload": { "ip": "1.2.3.4" } }
{ "type": "CREATE_ROOM","payload": { "name": "VIP Room", "maxPlayers": 10 } }
{ "type": "CLOSE_ROOM", "payload": { "roomId": "abc123" } }
{ "type": "SET_ROOM_STATE", "payload": { "roomId": "abc123", "state": "PLAYING" } }
{ "type": "ADMIN_BROADCAST", "payload": { "roomId": "abc123", "tag": "ROUND_START", "data": {} } }
{ "type": "ADMIN_SEND",      "payload": { "playerId": "xyz", "tag": "HINT", "data": {} } }
{ "type": "LIST_ROOMS",  "payload": {} }
{ "type": "GET_STATS",   "payload": {} }
{ "type": "GET_BAN_LIST","payload": {} }
```

### Programmatic (server-side)
```java
AdminService admin = server.getAdmin();
admin.kick("playerId", "AFK");
admin.ban("playerId", "Cheating");
admin.banIp("1.2.3.4");
admin.createRoom("VIP", 10);
admin.closeRoom("roomId");
admin.changeRoomState("roomId", Room.RoomState.PLAYING);
admin.broadcast("roomId", "ROUND_START", new JSONObject());
admin.sendTo("playerId", "HINT", new JSONObject().put("text", "Try again!"));
```

---

## Wire Protocol

### Inbound (Client → Server)
```json
{ "type": "<MsgType>", "payload": { ... } }
```

| Message | Required payload | Notes |
|---|---|---|
| `JOIN` | `name` (+ optional `token`, `roomId`, `password`) | Try reconnect if token present |
| `RECONNECT` | `token` | Legacy reconnect |
| `JOIN_ROOM` | `roomId` (+ optional `password`) | Move to a room |
| `LEAVE_ROOM` | — | Leave current room |
| `ADMIN_AUTH` | `token` | Must send before any admin command |
| `CUSTOM` | `tag`, `data?` | Forward to `dispatchCustom()` |

### Outbound (Server → Client)
```json
{ "type": "<MsgType>", "payload": { ... }, "ts": 1700000000000 }
```

Key messages: `WELCOME`, `RECONNECTED`, `PLAYER_JOINED`, `PLAYER_LEFT`,
`ROOM_INFO`, `ROOM_STATE_CHANGED`, `KICKED`, `BANNED`, `ADMIN_AUTH_OK`,
`CUSTOM_MSG`, `ERROR`

---

## Extending for Game Logic

```java
// 1. Extend MessageDispatcher
class MyDispatcher extends MessageDispatcher {
    public MyDispatcher(PlayerManager pm, RoomManager rm, EventBus bus, String adminToken) {
        super(pm, rm, bus, adminToken);
    }

    @Override
    protected void dispatchCustom(String connId, InboundMsg msg, IClientHandler handler) {
        String tag = msg.getString("tag", "");
        switch (tag) {
            case "ROLL_DICE": handleRollDice(connId, msg, handler); break;
            case "BUY_ITEM":  handleBuyItem(connId, msg, handler);  break;
            default: super.dispatchCustom(connId, msg, handler);
        }
    }

    private void handleRollDice(String connId, InboundMsg msg, IClientHandler handler) {
        int result = (int)(Math.random() * 6) + 1;
        Player p = playerManager.getByConnId(connId);
        Room   r = roomManager.getRoom(p.getRoomId());
        // Broadcast to room
        JSONObject data = new JSONObject().put("player", p.getId()).put("result", result);
        roomManager.broadcastToRoom(r, OutboundMsg.custom("DICE_ROLLED", data).toJson(), null);
    }
}

// 2. Extend Player/Room for game state
class LotoPlayer extends Player {
    public List<Page> pages = new ArrayList<>();
    public long balance = 0;
    public LotoPlayer(String name) { super(name); }
}

// 3. Wire it all together
SocketBaseServer server = new SocketBaseServer.Builder()
    .config(config)
    .dispatcher(new MyDispatcher(...))  // or build via factory
    .build();
```

---

## Broadcast Helpers

```java
import com.huydt.socket_base.util.Broadcast;

Broadcast.toRoom(roomManager, room, "ROUND_END", payload);
Broadcast.toAll(playerManager, "MAINTENANCE", new JSONObject().put("minutes", 5));
Broadcast.toPlayer(playerManager, "player123", "REWARD", rewardJson);
Broadcast.toPlayers(subset, "BONUS", data, playerManager);
```

---

## Dependencies

```gradle
api 'org.java-websocket:Java-WebSocket:1.5.4'
api 'org.json:json:20240303'
```

---

## Room States

```
WAITING → STARTING → PLAYING → PAUSED → ENDED → CLOSED
```
Custom states: extend `Room.RoomState` or store in `room.setMeta("customState", "MY_STATE")`.

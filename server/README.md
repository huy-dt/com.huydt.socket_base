# socket_base — Server SDK

Package: `com.huydt.socket_base.server`

---

## Quickstart

```java
SocketBaseServer server = new SocketBaseServer.Builder()
    .config(new ServerConfig.Builder()
        .wsPort(9001)
        .adminToken("my-secret")
        .reconnectTimeoutMs(30_000)
        .build())
    .build();

server.getEventBus().on(EventType.PLAYER_JOINED, e ->
    System.out.println("Welcome: " + e.getPlayer().getName()));

new Thread(server::startSafe).start();
```

---

## ServerConfig

```java
new ServerConfig.Builder()
    .port(9000)                    // TCP port (default 9000)
    .wsPort(9001)                  // WebSocket port (default 9001)
    .transport(TransportMode.WS)   // TCP | WS | BOTH  (default WS)
    .adminToken("secret")          // auto-generated UUID if omitted
    .reconnectTimeoutMs(30_000)    // ghost window in ms (default 30 000)
    .maxPlayersPerRoom(8)          // 0 = unlimited
    .maxRooms(100)                 // 0 = unlimited
    .autoCreateDefaultRoom(false)  // creates room "default" on start (default false)
    .persistPath("data.json")      // null = no persistence
    .build()
```

CLI flags: `--tcp` `--ws` `--both` `--port <n>` `--wsport <n>` `--admin-token <s>`

---

## Architecture — extension map

```
SocketBaseServer
  └─ createPlayerManager(config, bus)    →  PlayerManager
  │     └─ createPlayer(name)            →  Player
  └─ createRoomManager(config, pm, bus)  →  RoomManager
  │     └─ newRoom(id, name, max)        →  Room
  └─ createDispatcher(pm, rm, bus, tok)  →  MessageDispatcher
        └─ dispatchCustom(connId, msg, handler)
```

Override any of these factory methods in a `SocketBaseServer` subclass to inject
your own classes. Networking, reconnect, ghost timeouts, and admin routing are
handled by the SDK.

---

## Models

### `BaseModel`

Abstract base class for `Player` and `Room`. Auto-generates a 12-char `id` on
construction.

| Method | Contract |
|--------|----------|
| `toJson()` | Call `super.toJson()`, add your fields to the returned `JSONObject`, return it. |
| `fromJson(JSONObject j)` | Call `super.fromJson(j)` first, then restore your fields with `j.optXxx()`. |

---

### `Player` — extending

Extend to add game-specific fields (money, score, pages, etc.).

**Built-in fields:** `id`, `name`, `token`, `connId`, `roomId`, `connected`,
`isAdmin`, `joinedAt`, `disconnectedAt`, `metadata`

**Constructor:**
```java
public LotoPlayer(String name) { super(name); }
```

**Three methods to override:**

```java
public class LotoPlayer extends Player {

    public long money     = 1_000;
    public int  pageCount = 0;

    public LotoPlayer(String name) { super(name); }

    /**
     * toJson() is used for:
     *   — WELCOME payload        (sent only to this player, contains private token)
     *   — RECONNECTED payload    (same)
     *   — persistence writes
     *
     * Always call super first. All subclass fields are included here.
     */
    @Override
    public JSONObject toJson() {
        JSONObject j = super.toJson();  // id, name, token, roomId, connected, isAdmin, …
        j.put("money",     money);
        j.put("pageCount", pageCount);
        return j;
    }

    /**
     * toPublicJson() is used for:
     *   — PLAYER_JOINED  broadcast to room members
     *   — ROOM_SNAPSHOT  players[] array
     *   — APP_SNAPSHOT   players[] array
     *   — PLAYER_UPDATE  broadcast
     *
     * Default strips "token", "disconnectedAt", "metadata" from toJson().
     * Override to expose game fields that are safe to share publicly.
     * Never put private data (token, balance, etc.) here.
     */
    @Override
    public JSONObject toPublicJson() {
        JSONObject j = super.toPublicJson();  // token already removed
        j.put("pageCount", pageCount);        // safe to broadcast
        // do NOT add money here
        return j;
    }

    /**
     * fromJson() is used when restoring a player from persistence.
     * Always call super first.
     */
    @Override
    public void fromJson(JSONObject j) {
        super.fromJson(j);
        this.money     = j.optLong("money",    1_000);
        this.pageCount = j.optInt("pageCount", 0);
    }
}
```

**Metadata shortcut** (no subclass needed for simple values):
```java
player.setMeta("score", 42);
int score = (int) player.getMeta("score");
```

---

### `Room` — extending

Extend to add game state (jackpot, round number, drawn numbers, etc.).

**Built-in fields:** `id`, `name`, `state` (`RoomState`), `maxPlayers`,
`isPrivate`, `password`, `metadata`

**Built-in RoomState values:** `WAITING` `STARTING` `PLAYING` `PAUSED` `ENDED` `CLOSED`

**Constructors:**
```java
// auto-generated id (use when creating via admin command)
public LotoRoom(String name, int maxPlayers) { super(name, maxPlayers); }
// explicit id (required by RoomManager.newRoom)
public LotoRoom(String id, String name, int maxPlayers) { super(id, name, maxPlayers); }
```

**One method to override:**

```java
public class LotoRoom extends Room {

    public long jackpot = 0;
    public int  round   = 0;
    public List<Integer> drawnNumbers = new ArrayList<>();

    public LotoRoom(String id, String name, int maxPlayers) {
        super(id, name, maxPlayers);
    }

    /**
     * toJson() is used for:
     *   — ROOM_SNAPSHOT  (full room state pushed to clients)
     *   — ROOM_INFO      (response to GET_ROOM admin command)
     *   — APP_SNAPSHOT   rooms[] array
     *   — WELCOME / RECONNECTED room field
     *
     * The players[] array inside is built automatically by calling
     * player.toPublicJson() on each member — no extra work needed here.
     * Always call super first.
     */
    @Override
    public JSONObject toJson() {
        JSONObject j = super.toJson();  // id, name, state, maxPlayers, players[], …
        j.put("jackpot",      jackpot);
        j.put("round",        round);
        j.put("drawnNumbers", new JSONArray(drawnNumbers));
        return j;
    }
}
```

**Metadata shortcut:**
```java
room.setMeta("bet", 500);
int bet = (int) room.getMeta("bet");
```

**Player management (already handled by Room internally):**
```java
room.addPlayer(player)        // returns false if full or already inside
room.removePlayer(playerId)
room.hasPlayer(playerId)
room.getPlayer(playerId)
room.getPlayers()             // insertion-ordered immutable snapshot
room.isFull()
room.isEmpty()
room.getConnectedCount()
```

---

## Wiring custom models — `SocketBaseServer` subclass

```java
public class LotoServer extends SocketBaseServer {

    public LotoServer(ServerConfig config) { super(config); }

    @Override
    protected PlayerManager createPlayerManager(ServerConfig config, EventBus bus) {
        return new LotoPlayerManager(config, bus);
    }

    @Override
    protected RoomManager createRoomManager(ServerConfig config, PlayerManager pm, EventBus bus) {
        return new LotoRoomManager(config, pm, bus);
    }

    @Override
    protected MessageDispatcher createDispatcher(PlayerManager pm, RoomManager rm,
                                                  EventBus bus, String adminToken) {
        return new LotoDispatcher(pm, rm, bus, adminToken);
    }
}
```

### `PlayerManager` — custom player factory

```java
public class LotoPlayerManager extends PlayerManager {

    public LotoPlayerManager(ServerConfig config, EventBus bus) { super(config, bus); }

    /**
     * Called for every fresh JOIN.
     * The returned player is registered, tracked, and passed to all events.
     */
    @Override
    protected Player createPlayer(String name) {
        return new LotoPlayer(name);
    }
}
```

### `RoomManager` — custom room factory

```java
public class LotoRoomManager extends RoomManager {

    public LotoRoomManager(ServerConfig config, PlayerManager pm, EventBus bus) {
        super(config, pm, bus);
    }

    /**
     * Called by createRoom() after all validation passes.
     * id, name, and maxPlayers are already resolved by the caller.
     */
    @Override
    protected Room newRoom(String id, String name, int maxPlayers) {
        return new LotoRoom(id, name, maxPlayers);
    }
}
```

---

## `MessageDispatcher` — custom message routing

Override `dispatchCustom` to handle game-specific messages. The client sends
these using `MsgType.CUSTOM` with a `tag` field.

```java
public class LotoDispatcher extends MessageDispatcher {

    public LotoDispatcher(PlayerManager pm, RoomManager rm,
                          EventBus bus, String adminToken) {
        super(pm, rm, bus, adminToken);
    }

    /**
     * Called for every CUSTOM message after JOIN handshake is complete.
     * Cast to your subclass safely — createPlayer() always returns LotoPlayer.
     */
    @Override
    protected void dispatchCustom(String connId, InboundMsg msg, IClientHandler handler) {
        Player player = playerManager.getByConnId(connId);
        if (player == null) return;

        String tag      = msg.getString("tag");
        JSONObject data = msg.getObject("data");

        switch (tag != null ? tag : "") {
            case "BUY_TICKET":  handleBuyTicket((LotoPlayer) player, data, handler); break;
            case "START_ROUND": handleStartRound((LotoPlayer) player, handler);      break;
            default:
                handler.send(OutboundMsg.error("UNKNOWN_TAG",
                        "Unknown tag: " + tag).toJson());
        }
    }

    private void handleBuyTicket(LotoPlayer player, JSONObject data, IClientHandler handler) {
        int price = data != null ? data.optInt("price", 0) : 0;
        if (player.money < price) {
            handler.send(OutboundMsg.error("INSUFFICIENT_FUNDS", "Not enough money").toJson());
            return;
        }
        player.money -= price;
        player.pageCount++;

        // Push updated player state to everyone in the room
        if (player.getRoomId() != null) {
            roomManager.broadcastToRoom(player.getRoomId(),
                OutboundMsg.playerUpdate(player).toJson(), null);
        }
    }
}
```

**Protected helpers available in `MessageDispatcher` subclasses:**

| Method | When to call |
|--------|--------------|
| `sendSnapshotTo(handler, room)` | After state changes that affect one client only |
| `notifyLobby(excludeConnId)` | After join/leave/room create-close — lobby players see updated list |
| `notifyRoom(room, excludeConnId)` | After room-level state change — room members get ROOM_SNAPSHOT |
| `isAdmin(connId)` | Check before performing admin operations |
| `requireAdmin(connId, handler, action)` | Run `action` only if authenticated admin |

---

## `PlayerManager.onPermanentRemove` hook

Called on the scheduler thread when a ghost player's reconnect window expires.
The default hook in `MessageDispatcher` already removes the player from their room
and notifies lobby — override only for additional game logic.

```java
playerManager.onPermanentRemove = removedPlayer -> {
    String roomId = removedPlayer.getRoomId();  // still set at this point
    if (roomId != null) {
        LotoRoom room = (LotoRoom) roomManager.getRoom(roomId);
        if (room != null) {
            room.removePlayer(removedPlayer.getId());
            roomManager.broadcastToRoom(room,
                OutboundMsg.playerLeft(removedPlayer.getId(), true).toJson(), null);
            // game-specific cleanup
            room.cancelPlayerTickets(removedPlayer.getId());
        }
    }
};
```

---

## Admin API (`AdminService`)

```java
AdminService admin = server.getAdmin();

// Player moderation
admin.kick("playerId", "AFK");
admin.ban("playerId", "Cheating");
admin.unban("Alice");              // by display name
admin.banIp("1.2.3.4");
admin.unbanIp("1.2.3.4");

// Room management
admin.createRoom("Room 1");
admin.createRoom("room-id", "Room 1", 8);
admin.closeRoom("roomId");
admin.changeRoomState("roomId", Room.RoomState.PLAYING);

// Messaging
admin.broadcast("roomId", "ROUND_START", payload);  // roomId=null → all players
admin.sendTo("playerId", "YOUR_TURN", payload);
admin.sendRaw("playerId", rawJson);

// Push updates (fire-and-forget, no return value)
admin.pushPlayerUpdate(player);   // PLAYER_UPDATE → player + all room members
admin.pushRoomUpdate(room);        // ROOM_UPDATE   → all room members
admin.pushRoomUpdate("roomId");

// Queries
admin.getPlayer("playerId");
admin.getAllPlayers();
admin.getRoom("roomId");
admin.getAllRooms();
admin.getPlayerCount();
admin.getRoomCount();
```

---

## Event bus

```java
EventBus bus = server.getEventBus();

bus.on(EventType.PLAYER_JOINED,       e -> { Player p = e.getPlayer(); ... });
bus.on(EventType.PLAYER_DISCONNECTED, e -> { ... });
bus.on(EventType.PLAYER_RECONNECTED,  e -> { ... });
bus.on(EventType.PLAYER_LEFT,         e -> { ... });  // ghost timeout expired
bus.on(EventType.PLAYER_KICKED,       e -> { ... });
bus.on(EventType.PLAYER_BANNED,       e -> { ... });
bus.on(EventType.ROOM_CREATED,        e -> { Room r = e.getRoom(); ... });
bus.on(EventType.ROOM_CLOSED,         e -> { ... });
bus.on(EventType.ROOM_PLAYER_JOINED,  e -> { Player p = e.getPlayer(); Room r = e.getRoom(); ... });
bus.on(EventType.ROOM_PLAYER_LEFT,    e -> { ... });
bus.on(EventType.ROOM_STATE_CHANGED,  e -> { ... });
bus.on(EventType.CUSTOM,              e -> { String tag = e.getMessage(); ... });
bus.on(EventType.SERVER_STARTED,      e -> { ... });
bus.on(EventType.SERVER_STOPPED,      e -> { ... });
```

---

## Reconnect flow

```
Client drops
    → player marked connected=false (ghost)
    → PLAYER_LEFT(permanent=false) broadcast to room
    → reconnect timer starts (reconnectTimeoutMs)

Client reconnects with saved token within window
    → PLAYER_RECONNECTED broadcast to room
    → session restored, room membership kept

Timer expires before reconnect
    → onPermanentRemove fires
    → PLAYER_LEFT(permanent=true) broadcast to room
    → player removed from all maps
```

Token management is fully automatic — the client SDK saves and resends the token.
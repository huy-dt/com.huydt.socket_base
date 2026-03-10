# socket_base — Client SDK

Package: `com.huydt.socket_base.client`

---

## Quickstart

```java
SocketBaseClient client = new SocketBaseClient.Builder()
    .host("localhost")
    .port(9001)
    .name("Alice")
    .build();

client.on(ClientEventType.WELCOME, e ->
    System.out.println("Joined! token=" + client.getSession().getToken()));

client.connect();
```

---

## ClientConfig

```java
new ClientConfig.Builder()
    .host("localhost")          // default localhost
    .port(9001)                 // default 9001
    .useSsl(false)              // WSS (default false)
    .connectTimeoutMs(5_000)    // initial connect timeout (default 5 000)
    .reconnectTimeoutMs(0)      // mirrors server ghost window; 0 = no hint (default 0)
    .build()
```

URI is built automatically: `ws://host:port` or `wss://host:port`.

---

## Builder shortcuts

```java
new SocketBaseClient.Builder()
    .host("game.example.com")
    .port(9001)
    .useSsl(true)
    .name("Alice")                           // display name sent with JOIN
    .roomId("room-abc")                      // auto-join this room after WELCOME
    .reconnectToken(savedToken)              // restore previous session
    .adminToken("server-secret")             // auto-sends ADMIN_AUTH before JOIN
    .build();
```

---

## Models

The client mirrors the server model hierarchy. Override `RoomInfo` and `PlayerInfo`
to add game-specific fields, then tell the session to use your subclasses via
`setRoomFactory`.

### `PlayerInfo` — extending

```java
public class LotoPlayerInfo extends PlayerInfo {

    public long money     = 0;
    public int  pageCount = 0;

    public LotoPlayerInfo(JSONObject j) { super(j); }

    /**
     * merge() is called on every player JSON update:
     *   — ROOM_SNAPSHOT  players[] (via replaceFrom → createPlayer → constructor → merge)
     *   — PLAYER_JOINED
     *   — PLAYER_UPDATE  (⚠ may be partial — only changed keys are present)
     *   — PLAYER_RECONNECTED
     *
     * Always call super first.
     * Only update a field when the key is present in j — never overwrite with a
     * default when the key is absent (partial updates would lose data otherwise).
     */
    @Override
    public void merge(JSONObject j) {
        super.merge(j);                                     // id, name, roomId, connected, isAdmin
        if (j.has("money"))     this.money     = j.optLong("money",    0);
        if (j.has("pageCount")) this.pageCount = j.optInt("pageCount", 0);
    }
}
```

---

### `RoomInfo` — extending

```java
public class LotoRoomInfo extends RoomInfo {

    public long bet   = 0;
    public int  round = 0;
    public List<Integer> drawnNumbers = new ArrayList<>();

    /**
     * mergeRoom() is called on every room JSON update:
     *   — ROOM_SNAPSHOT / WELCOME  (via replaceFrom, before players are rebuilt)
     *   — ROOM_UPDATE              (⚠ may be partial — only changed keys)
     *   — ROOM_STATE_CHANGED       (merged into current room if roomId matches)
     *
     * Always call super first.
     * Only update a field when the key is present in j.
     */
    @Override
    public void mergeRoom(JSONObject j) {
        super.mergeRoom(j);                               // id, name, state, maxPlayers, isPrivate
        if (j.has("bet"))   this.bet   = j.optLong("bet",  0);
        if (j.has("round")) this.round = j.optInt("round", 0);
        if (j.has("drawnNumbers")) {
            JSONArray arr = j.optJSONArray("drawnNumbers");
            if (arr != null) {
                drawnNumbers.clear();
                for (int i = 0; i < arr.length(); i++) drawnNumbers.add(arr.optInt(i));
            }
        }
    }

    /**
     * createPlayer() is called for every player entry in a ROOM_SNAPSHOT players[]
     * and for every PLAYER_JOINED event.
     * Return your PlayerInfo subclass here.
     */
    @Override
    protected PlayerInfo createPlayer(JSONObject j) {
        return new LotoPlayerInfo(j);
    }
}
```

---

## Wiring custom models into the session

Set the factory **before** `connect()` so the very first WELCOME/ROOM_SNAPSHOT
already uses your subclass.

```java
SocketBaseClient client = new SocketBaseClient.Builder()
    .host("localhost").port(9001).name("Alice")
    .build();

client.getSession().setRoomFactory(LotoRoomInfo::new);

client.connect();
```

After this, `session.getCurrentRoom()` returns a `LotoRoomInfo` and every player
inside it is a `LotoPlayerInfo`.

```java
client.on(ClientEventType.ROOM_SNAPSHOT, e -> {
    LotoRoomInfo room = (LotoRoomInfo) client.getSession().getCurrentRoom();
    System.out.println("bet=" + room.bet + " round=" + room.round);

    for (PlayerInfo pi : room.getPlayers().values()) {
        LotoPlayerInfo lp = (LotoPlayerInfo) pi;
        System.out.println(lp.name + " pages=" + lp.pageCount);
    }
});
```

---

## `SocketBaseClient` subclass — custom message routing

Override `dispatchCustom` to route `CUSTOM_MSG` by tag instead of emitting a
generic bus event.

```java
public class LotoClient extends SocketBaseClient {

    public LotoClient(Builder b) { super(b); }

    /**
     * Called for every CUSTOM_MSG received from the server.
     * Default: emits ClientEventType.CUSTOM_MSG to the event bus.
     * Override to route by tag for cleaner game logic.
     */
    @Override
    protected void dispatchCustom(InboundMsg msg, JSONObject payload) {
        String tag = payload.optString("tag");
        JSONObject data = payload.optJSONObject("data");

        switch (tag != null ? tag : "") {
            case "ROUND_START":  onRoundStart(data);  break;
            case "NUMBER_DRAWN": onNumberDrawn(data); break;
            case "GAME_OVER":    onGameOver(data);    break;
            default:
                super.dispatchCustom(msg, payload);  // falls through to CUSTOM_MSG event
        }
    }

    private void onRoundStart(JSONObject data)  { /* ... */ }
    private void onNumberDrawn(JSONObject data) { /* ... */ }
    private void onGameOver(JSONObject data)    { /* ... */ }
}
```

Build with the subclass — the `Builder` constructor is `protected`:

```java
LotoClient client = new LotoClient(
    new SocketBaseClient.Builder()
        .host("localhost").port(9001).name("Alice")
);
client.getSession().setRoomFactory(LotoRoomInfo::new);
client.connect();
```

---

## Sending messages

```java
// Game messages
client.joinRoom("roomId");
client.joinRoom("roomId", "password");
client.leaveRoom();
client.custom("READY");
client.custom("BUY_TICKET", new JSONObject().put("price", 500));

// Admin (requires adminToken set in builder, or manual adminAuth call)
client.kick("playerId", "AFK");
client.ban("playerId", "Cheating");
client.unban("Alice");
client.banIp("1.2.3.4");
client.createRoom("Room 1");
client.createRoom("Room 1", 8, "password");
client.closeRoom("roomId");
client.setRoomState("roomId", "PLAYING");
client.adminBroadcast("roomId", "HINT", data);   // roomId=null → all players
client.adminSend("playerId", "YOUR_TURN", data);
client.getStats();
client.getBanList();
client.listRooms();
client.getRoom("roomId");
```

All send methods return `boolean` — `false` if not currently connected.

---

## `ClientSession` — reading state

```java
ClientSession s = client.getSession();

s.getPlayerId()        // our stable player id
s.getToken()           // reconnect token — save to prefs for next launch
s.getName()            // our display name
s.getRoomId()          // current room id, null = in lobby
s.isAdmin()            // true after ADMIN_AUTH_OK
s.isLoggedIn()         // true after WELCOME / RECONNECTED
s.isInRoom()           // true if currently in a room

s.getCurrentRoom()     // RoomInfo (or LotoRoomInfo), null if in lobby
s.getPlayer("id")      // PlayerInfo from current room
```

`ClientSession` is **not thread-safe** — read it only from the WS callback thread
(inside event listeners). If you need to read from the UI thread, take a copy of
the fields you need inside the listener.

---

## Reconnect with saved token

```java
// Save after first connect
client.on(ClientEventType.WELCOME, e -> {
    prefs.putString("token", client.getSession().getToken());
});

// On next launch
SocketBaseClient client = new SocketBaseClient.Builder()
    .host("localhost").port(9001).name("Alice")
    .reconnectToken(prefs.getString("token", null))
    .build();
client.getSession().setRoomFactory(LotoRoomInfo::new);
client.connect();
// If token is still valid → RECONNECTED fires instead of WELCOME
// If token expired → server falls through to fresh JOIN → WELCOME fires
```

---

## Full event reference

| Event | Fires when | Useful payload keys |
|-------|-----------|---------------------|
| `CONNECTED` | Socket opened, before JOIN is sent | — |
| `DISCONNECTED` | Socket closed | `message` = reason |
| `WELCOME` | Fresh JOIN accepted — we have id + token | `player { id, token, name, … }`, `room` |
| `RECONNECTED` | Token reconnect accepted | same as WELCOME |
| `APP_SNAPSHOT` | Server pushed lobby state | `players[]`, `rooms[]` |
| `ROOM_SNAPSHOT` | Server pushed full room state | `room { id, name, state, players[], … }` |
| `ROOM_INFO` | Response to `getRoom()` | `room` |
| `ROOM_LIST` | Response to `listRooms()` | `rooms[]` |
| `ROOM_STATE_CHANGED` | Room state changed | `roomId`, `oldState`, `newState` |
| `ROOM_UPDATE` | Partial room fields changed | `room { … }` |
| `PLAYER_JOINED` | Someone joined our room | `player { … }` |
| `PLAYER_LEFT` | Someone left / disconnected | `playerId`, `permanent` |
| `PLAYER_RECONNECTED` | Ghost came back online | `player { … }` |
| `PLAYER_UPDATE` | A player's fields changed | `player { id, … }` |
| `ADMIN_AUTH_OK` | Admin auth succeeded | — |
| `BAN_LIST` | Response to `getBanList()` | `playerIds[]`, `ips[]` |
| `STATS` | Response to `getStats()` | `rooms`, `players`, `uptimeSec` |
| `KICKED` | We were kicked | `reason` |
| `BANNED` | We were banned | `reason` |
| `CUSTOM_MSG` | Server sent a custom event | `tag`, `data` |
| `ERROR` | Server error or local exception | `code`, `detail` |

---

## `PLAYER_LEFT` — permanent vs ghost

| `permanent` | Meaning | `RoomInfo` action |
|-------------|---------|-------------------|
| `false` | Disconnected — ghost slot kept | `markOffline(playerId)` → player stays in list, `connected=false` |
| `true` | Left for good (timeout / kick / ban / room switch) | `removePlayer(playerId)` → removed from list |

Handle in your UI:
```java
client.on(ClientEventType.PLAYER_LEFT, e -> {
    String  playerId  = e.getPayload().optString("playerId");
    boolean permanent = e.getPayload().optBoolean("permanent", false);

    if (permanent) {
        showToast(playerId + " left");
    } else {
        markPlayerOffline(playerId);   // dim avatar, show "disconnected" badge
    }
});
```
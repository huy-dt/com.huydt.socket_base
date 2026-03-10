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

client.on(ClientEventType.WELCOME, e -> {
    System.out.println("Joined! token=" + client.getSession().getToken());
});

client.connect();
```

---

## ClientConfig

```java
new ClientConfig.Builder()
    .host("localhost")                    // default localhost
    .port(9001)                           // default 9001
    .useSsl(false)                        // WSS (default false)
    .connectTimeoutMs(5_000)              // initial connect timeout (default 5 000)
    .autoReconnect(true)                  // reconnect on unexpected drop (default true)
    .reconnectDelayMs(2_000)              // first retry delay (default 2 000)
    .reconnectBackoffMultiplier(1.5)      // exponential backoff factor (default 1.5)
    .reconnectMaxDelayMs(30_000)          // cap on delay (default 30 000)
    .reconnectMaxAttempts(0)              // 0 = unlimited (default 0)
    .heartbeatIntervalMs(15_000)          // client-side PING interval (default 15 000)
    .heartbeatTimeoutMs(5_000)            // PONG wait before closing (default 5 000)
    .build()
```

---

## Auto-reconnect

Enabled by default. On unexpected disconnect the client retries with
exponential backoff:

```
attempt 1: wait 2 000 ms
attempt 2: wait 3 000 ms
attempt 3: wait 4 500 ms
…capped at 30 000 ms
```

The token + generation are sent automatically — if the server still recognises
the session, a `RECONNECTED` event fires instead of `WELCOME`.

Call `client.disconnect()` to stop permanently.

```java
client.on(ClientEventType.RECONNECTING,    e -> showToast("Reconnecting… " + e.getMessage()));
client.on(ClientEventType.RECONNECT_FAILED,e -> showError("Gave up reconnecting"));
client.on(ClientEventType.HEARTBEAT_TIMEOUT, e -> log("Connection dead — reconnecting"));
```

---

## Models

The client mirrors the server model hierarchy. Override `RoomInfo` and
`PlayerInfo` to add game-specific fields; tell the session to use your
subclasses via `setRoomFactory`.

### `PlayerInfo` — extending

```java
public class LotoPlayerInfo extends PlayerInfo {

    public int  pageCount = 0;
    public boolean hasBingo = false;

    public LotoPlayerInfo(JSONObject j) { super(j); }

    /**
     * merge() is called on:
     *   - initial ROOM_SNAPSHOT (via replaceFrom → createPlayer)
     *   - PLAYER_JOINED
     *   - PLAYER_UPDATE       (partial JSON — only changed keys present)
     *   - PLAYER_RECONNECTED
     *
     * Always call super first. Only update a field if the key is present
     * in j — never overwrite with a default when the key is absent.
     */
    @Override
    public void merge(JSONObject j) {
        super.merge(j);                                    // id, name, roomId, connected, isAdmin
        if (j.has("pageCount")) this.pageCount = j.optInt("pageCount", 0);
        if (j.has("hasBingo"))  this.hasBingo  = j.optBoolean("hasBingo", false);
    }
}
```

---

### `RoomInfo` — extending

```java
public class LotoRoomInfo extends RoomInfo {

    public long jackpot = 0;
    public int  round   = 0;
    public List<Integer> drawnNumbers = new ArrayList<>();

    /**
     * mergeRoom() is called on:
     *   - ROOM_SNAPSHOT / WELCOME room object (via replaceFrom)
     *   - ROOM_UPDATE  (partial JSON — only changed keys)
     *   - ROOM_STATE_CHANGED (merged into current room)
     *
     * Always call super first.
     */
    @Override
    public void mergeRoom(JSONObject j) {
        super.mergeRoom(j);                             // id, name, state, maxPlayers, isPrivate
        if (j.has("jackpot")) this.jackpot = j.optLong("jackpot", 0);
        if (j.has("round"))   this.round   = j.optInt("round", 0);
        if (j.has("drawnNumbers")) {
            JSONArray arr = j.optJSONArray("drawnNumbers");
            if (arr != null) {
                drawnNumbers.clear();
                for (int i = 0; i < arr.length(); i++) drawnNumbers.add(arr.optInt(i));
            }
        }
    }

    /**
     * createPlayer() is called for every player in a ROOM_SNAPSHOT players array
     * and for each PLAYER_JOINED event.
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

```java
SocketBaseClient client = new SocketBaseClient.Builder()
    .host("localhost").port(9001).name("Alice")
    .build();

// Must be set before connect() so the first WELCOME/ROOM_SNAPSHOT uses it
client.getSession().setRoomFactory(LotoRoomInfo::new);

client.connect();
```

After this, every `client.getSession().getCurrentRoom()` returns a
`LotoRoomInfo`, and every player inside it is a `LotoPlayerInfo`.

```java
client.on(ClientEventType.ROOM_SNAPSHOT, e -> {
    LotoRoomInfo room = (LotoRoomInfo) client.getSession().getCurrentRoom();
    System.out.println("jackpot=" + room.jackpot + " round=" + room.round);

    for (PlayerInfo pi : room.getPlayers().values()) {
        LotoPlayerInfo lp = (LotoPlayerInfo) pi;
        System.out.println(lp.name + " pages=" + lp.pageCount);
    }
});
```

---

## `SocketBaseClient` — custom message routing

For game-specific `CUSTOM_MSG` events, override `dispatchCustom`:

```java
public class LotoClient extends SocketBaseClient {

    public LotoClient(Builder b) { super(b); }

    /**
     * Called for every CUSTOM_MSG received from the server.
     * Default behaviour is to emit ClientEventType.CUSTOM_MSG to the bus.
     * Override to route by tag instead.
     */
    @Override
    protected void dispatchCustom(InboundMsg msg, JSONObject payload) {
        String tag = payload.optString("tag");
        JSONObject data = payload.optJSONObject("data");

        switch (tag != null ? tag : "") {
            case "ROUND_START":
                onRoundStart(data);
                break;
            case "NUMBER_DRAWN":
                onNumberDrawn(data);
                break;
            default:
                // Fall back to emitting generic CUSTOM_MSG event
                super.dispatchCustom(msg, payload);
        }
    }

    private void onRoundStart(JSONObject data) { /* ... */ }
    private void onNumberDrawn(JSONObject data) { /* ... */ }
}
```

Then build with the subclass:

```java
LotoClient client = new LotoClient(
    new SocketBaseClient.Builder()
        .host("localhost").port(9001).name("Alice")
);
client.getSession().setRoomFactory(LotoRoomInfo::new);
client.connect();
```

---

## `ClientSession` — thread safety

All `ClientSession` methods are `synchronized`. The WS callback thread writes
(via `update()`, `onPlayerJoined()`, etc.) while the game/UI thread reads
(via `getCurrentRoom()`, `getPlayer()`, etc.) — both are safe without external
locking.

Do not hold a reference to `getCurrentRoom()` across yield points if you need a
consistent snapshot — call it again each time, or copy the data you need.

---

## Sending custom messages

```java
// No data
client.custom("READY");

// With data
client.custom("BUY_TICKET", new JSONObject().put("price", 500));
```

On the server, these arrive as `MsgType.CUSTOM` and are routed to
`MessageDispatcher.dispatchCustom()`.

---

## Reconnect & generation system

The SDK handles the full token + generation flow automatically:

1. On `WELCOME` / `RECONNECTED` the session stores `token` and `tokenGeneration`.
2. On reconnect, both values are sent in the `JOIN` payload.
3. If the server responds with `ERROR { code: "GENERATION_MISMATCH" }` (another
   device already claimed the session), `autoReconnect` is stopped permanently.

You do not need to manage generation manually. Save only the `token` if you
need cross-launch persistence:

```java
client.on(ClientEventType.WELCOME, e -> {
    String token = client.getSession().getToken();
    prefs.putString("reconnect_token", token);
    // tokenGeneration is NOT saved — it is tracked in-memory per session
});

// On next launch:
SocketBaseClient client = new SocketBaseClient.Builder()
    .reconnectToken(prefs.getString("reconnect_token", null))
    // generation is unknown for a saved token → SDK sends -1 → server skips check
    .build();
```

---

## Full event reference

| Event | When fires | Useful payload keys |
|-------|-----------|---------------------|
| `CONNECTED` | Socket opened, before JOIN | — |
| `DISCONNECTED` | Socket closed | `message` = reason |
| `RECONNECTING` | Auto-reconnect cycle started | `message` = "attempt=N delay=Xms" |
| `RECONNECT_FAILED` | Max attempts reached | `message` |
| `HEARTBEAT_TIMEOUT` | No PONG within timeout | `message` |
| `WELCOME` | Fresh join accepted | `player { id, token, tokenGeneration, … }`, `room` |
| `RECONNECTED` | Token reconnect accepted | same as WELCOME |
| `APP_SNAPSHOT` | Lobby state pushed | `players[]`, `rooms[]` |
| `ROOM_SNAPSHOT` | Room state pushed | `room { …, players[] }` |
| `ROOM_INFO` | Response to GET_ROOM | `room` |
| `ROOM_LIST` | Response to LIST_ROOMS | `rooms[]` |
| `ROOM_STATE_CHANGED` | Room state changed | `roomId`, `oldState`, `newState` |
| `ROOM_UPDATE` | Partial room fields changed | `room { … }` |
| `PLAYER_JOINED` | Someone joined our room | `player` |
| `PLAYER_LEFT` | Someone left / disconnected | `playerId`, `permanent` |
| `PLAYER_RECONNECTED` | Ghost came back | `player` |
| `PLAYER_UPDATE` | Player fields changed | `player { id, … }` |
| `ADMIN_AUTH_OK` | Admin auth succeeded | — |
| `BAN_LIST` | Response to GET_BAN_LIST | `playerIds[]`, `ips[]` |
| `STATS` | Response to GET_STATS | `rooms`, `players`, `uptimeSec` |
| `KICKED` | We were kicked | `reason` |
| `BANNED` | We were banned | `reason` |
| `CUSTOM_MSG` | Server sent custom event | `tag`, `data` |
| `ERROR` | Server error or local exception | `code`, `detail` |
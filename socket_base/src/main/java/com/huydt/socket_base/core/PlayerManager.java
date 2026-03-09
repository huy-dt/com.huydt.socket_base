package com.huydt.socket_base.core;

import com.huydt.socket_base.event.EventBus;
import com.huydt.socket_base.event.EventType;
import com.huydt.socket_base.event.ServerEvent;
import com.huydt.socket_base.model.Player;
import com.huydt.socket_base.network.IClientHandler;
import com.huydt.socket_base.protocol.OutboundMsg;

import java.util.*;
import java.util.concurrent.*;
import java.util.function.Consumer;
import java.util.stream.Collectors;

/**
 * Manages the full player lifecycle: connect, reconnect, disconnect/timeout.
 *
 * <p>Thread-safe. All methods that mutate player state are synchronized.
 *
 * <h3>Reconnect timeout</h3>
 * When a player's socket drops, they become a <em>ghost</em>: still in the
 * room, but marked disconnected. If they reconnect before
 * {@link ServerConfig#reconnectTimeoutMs} the slot is restored transparently.
 * If the timer fires first, {@link #permanentRemove} is called on the
 * scheduler thread — the {@link #onPermanentRemove} hook fires so the
 * dispatcher (or any other subscriber) can react (e.g. broadcast APP_SNAPSHOT).
 */
public class PlayerManager {

    private final ServerConfig config;
    private final EventBus     bus;

    /** playerId → Player */
    private final Map<String, Player>         playersById    = new ConcurrentHashMap<>();
    /** connId → Player (live connections only) */
    private final Map<String, Player>         playersByConn  = new ConcurrentHashMap<>();
    /** token → Player (includes ghosts waiting to reconnect) */
    private final Map<String, Player>         playersByToken = new ConcurrentHashMap<>();
    /** connId → IClientHandler */
    private final Map<String, IClientHandler> handlers       = new ConcurrentHashMap<>();
    /** playerId → pending removal ScheduledFuture */
    private final Map<String, ScheduledFuture<?>> pendingRemoval = new ConcurrentHashMap<>();

    private final ScheduledExecutorService scheduler = Executors.newScheduledThreadPool(1,
            r -> { Thread t = new Thread(r, "player-timeout"); t.setDaemon(true); return t; });

    // Ban lists
    private final Set<String> bannedIds = Collections.synchronizedSet(new HashSet<>());
    private final Set<String> bannedIps = Collections.synchronizedSet(new HashSet<>());

    /**
     * Optional hook called (on the scheduler thread) when a ghost player is
     * permanently removed after the reconnect timeout expires.
     *
     * <p>Register from {@link com.huydt.socket_base.network.MessageDispatcher}
     * so it can broadcast an APP_SNAPSHOT without needing a direct reference
     * to the dispatcher:
     * <pre>
     * playerManager.onPermanentRemove = removedPlayer -> broadcastAppSnapshot(null);
     * </pre>
     */
    public Consumer<Player> onPermanentRemove = null;

    public PlayerManager(ServerConfig config, EventBus bus) {
        this.config = config;
        this.bus    = bus;
    }

    // ── Connect / Reconnect ───────────────────────────────────────────

    /**
     * Factory method — override in a subclass to return your own Player subclass.
     * <pre>
     * &#64;Override protected Player createPlayer(String name) { return new LotoPlayer(name); }
     * </pre>
     */
    protected Player createPlayer(String name) {
        return new Player(name);
    }

    /**
     * Fresh join: creates a new Player, registers the handler, fires PLAYER_JOINED.
     */
    public synchronized Player join(String connId, String name, IClientHandler handler) {
        Player player = createPlayer(name);
        register(connId, player, handler);
        bus.emit(new ServerEvent.Builder(EventType.PLAYER_JOINED).player(player).build());
        return player;
    }

    /**
     * Reconnect using a previously issued token.
     * Cancels the pending removal timer and restores the live connection.
     * If the old connection is still alive, it is forcefully disconnected first.
     *
     * @return the Player on success, null if token not found / expired
     */
    public synchronized Player reconnect(String connId, String token, IClientHandler handler) {
        Player player = playersByToken.get(token);
        if (player == null) return null;

        // Cancel pending removal timer
        ScheduledFuture<?> fut = pendingRemoval.remove(player.getId());
        if (fut != null) fut.cancel(false);

        // Forcefully close the old connection if it is still alive
        String oldConnId = player.getConnId();
        if (oldConnId != null && !oldConnId.equals(connId)) {
            IClientHandler oldHandler = handlers.remove(oldConnId);
            if (oldHandler != null) {
                try {
                    oldHandler.send(OutboundMsg.error("REPLACED",
                            "Your session was taken over by a new connection").toJson());
                    oldHandler.close();
                } catch (Exception e) {
                    System.err.println("[PlayerManager] Failed to close old connection: " + e.getMessage());
                }
            }
            playersByConn.remove(oldConnId);
            System.out.printf("[PlayerManager] Disconnected old connection '%s' for player '%s' (token reuse)%n",
                    oldConnId, player.getName());
        }

        player.markConnected(connId);
        handlers.put(connId, handler);
        playersByConn.put(connId, player);

        bus.emit(new ServerEvent.Builder(EventType.PLAYER_RECONNECTED).player(player).build());
        return player;
    }

    /**
     * Called by the transport layer when a socket drops.
     * The player becomes a ghost; permanent removal is scheduled after
     * {@link ServerConfig#reconnectTimeoutMs}.
     */
    public synchronized void onDisconnected(String connId) {
        Player player = playersByConn.remove(connId);
        if (player == null) return;

        handlers.remove(connId);
        player.markDisconnected();

        bus.emit(new ServerEvent.Builder(EventType.PLAYER_DISCONNECTED).player(player).build());

        if (config.reconnectTimeoutMs > 0) {
            ScheduledFuture<?> fut = scheduler.schedule(
                    () -> permanentRemove(player.getId()),
                    config.reconnectTimeoutMs,
                    TimeUnit.MILLISECONDS);
            pendingRemoval.put(player.getId(), fut);
            System.out.printf("[PlayerManager] Ghost '%s' — timeout in %dms%n",
                    player.getName(), config.reconnectTimeoutMs);
        } else {
            permanentRemove(player.getId());  // immediate
        }
    }

    /**
     * Final removal after timeout or immediate disconnect (timeout=0).
     * Fires {@link EventType#PLAYER_LEFT} and calls {@link #onPermanentRemove}.
     */
    private synchronized void permanentRemove(String playerId) {
        Player player = playersById.remove(playerId);
        if (player == null) return;  // already reconnected and re-registered

        playersByToken.remove(player.getToken());
        pendingRemoval.remove(playerId);

        System.out.printf("[PlayerManager] Permanently removed '%s' (timeout expired)%n",
                player.getName());

        bus.emit(new ServerEvent.Builder(EventType.PLAYER_LEFT).player(player).build());

        // Notify dispatcher (or any other subscriber) so it can broadcast APP_SNAPSHOT
        Consumer<Player> hook = onPermanentRemove;
        if (hook != null) {
            try { hook.accept(player); }
            catch (Exception e) {
                System.err.println("[PlayerManager] onPermanentRemove hook threw: " + e.getMessage());
            }
        }
    }

    // ── Kick / Ban ────────────────────────────────────────────────────

    public synchronized void kick(String playerId, String reason) {
        Player player = playersById.get(playerId);
        if (player == null) return;

        IClientHandler h = handlers.get(player.getConnId());
        if (h != null) {
            h.send(OutboundMsg.kicked(reason).toJson());
            h.close();
        }
        permanentRemove(playerId);
        bus.emit(new ServerEvent.Builder(EventType.PLAYER_KICKED)
                .player(player).message(reason).build());
    }

    public synchronized void ban(String playerId, String reason) {
        Player player = playersById.get(playerId);
        if (player != null) {
            bannedIds.add(playerId);
            IClientHandler h = handlers.get(player.getConnId());
            if (h != null) {
                h.send(OutboundMsg.banned(reason).toJson());
                bannedIps.add(h.getRemoteIp());
                h.close();
            }
            permanentRemove(playerId);
        } else {
            bannedIds.add(playerId);
        }
        bus.emit(new ServerEvent.Builder(EventType.PLAYER_BANNED)
                .message(playerId + ": " + reason).build());
    }

    public void banIp(String ip)    { bannedIps.add(ip); }
    public void unbanIp(String ip)  { bannedIps.remove(ip); }

    public void unban(String name) {
        playersById.values().stream()
                .filter(p -> p.getName().equalsIgnoreCase(name))
                .findFirst()
                .ifPresent(p -> bannedIds.remove(p.getId()));
    }

    public boolean isBanned(String playerId, String ip) {
        return (playerId != null && bannedIds.contains(playerId))
                || (ip != null && bannedIps.contains(ip));
    }

    // ── Send helpers ──────────────────────────────────────────────────

    public void sendTo(String connId, String json) {
        IClientHandler h = handlers.get(connId);
        if (h != null) h.send(json);
    }

    public void sendToPlayer(String playerId, String json) {
        Player p = playersById.get(playerId);
        if (p != null && p.getConnId() != null) sendTo(p.getConnId(), json);
    }

    public void broadcast(String json) { broadcast(json, null); }

    public void broadcast(String json, String excludeConnId) {
        for (Map.Entry<String, IClientHandler> e : handlers.entrySet()) {
            if (!e.getKey().equals(excludeConnId)) {
                e.getValue().send(json);
            }
        }
    }

    // ── Accessors ─────────────────────────────────────────────────────

    public Player           getByConnId(String connId)  { return playersByConn.get(connId); }
    public Player           getById(String playerId)    { return playersById.get(playerId); }
    public Player           getByToken(String token)    { return playersByToken.get(token); }
    public IClientHandler   getHandler(String connId)   { return handlers.get(connId); }

    public Collection<Player> getAllPlayers()    { return Collections.unmodifiableCollection(playersById.values()); }
    public int getTotalCount()                  { return playersById.size(); }

    public List<Player> getConnectedPlayers() {
        return playersById.values().stream().filter(Player::isConnected).collect(Collectors.toList());
    }

    public Set<String> getBannedIds() { return Collections.unmodifiableSet(bannedIds); }
    public Set<String> getBannedIps() { return Collections.unmodifiableSet(bannedIps); }

    // ── Internal ──────────────────────────────────────────────────────

    private void register(String connId, Player player, IClientHandler handler) {
        playersById.put(player.getId(), player);
        playersByToken.put(player.getToken(), player);
        playersByConn.put(connId, player);
        handlers.put(connId, handler);
        player.setConnId(connId);
    }

    public void shutdown() { scheduler.shutdownNow(); }
}

package com.huydt.socket_base.server.core;

import com.huydt.socket_base.server.event.EventBus;
import com.huydt.socket_base.server.event.EventType;
import com.huydt.socket_base.server.event.ServerEvent;
import com.huydt.socket_base.server.model.Player;
import com.huydt.socket_base.server.network.IClientHandler;
import com.huydt.socket_base.server.protocol.OutboundMsg;

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
 * dispatcher (or any other subscriber) can react.
 */
public class PlayerManager {

    private final ServerConfig config;
    private final EventBus     bus;

    private final Map<String, Player>         playersById    = new ConcurrentHashMap<>();
    private final Map<String, Player>         playersByConn  = new ConcurrentHashMap<>();
    private final Map<String, Player>         playersByToken = new ConcurrentHashMap<>();
    private final Map<String, IClientHandler> handlers       = new ConcurrentHashMap<>();
    private final Map<String, ScheduledFuture<?>> pendingRemoval = new ConcurrentHashMap<>();

    private final ScheduledExecutorService scheduler = Executors.newScheduledThreadPool(1,
            r -> { Thread t = new Thread(r, "player-timeout"); t.setDaemon(true); return t; });

    private final Set<String> bannedIds = Collections.synchronizedSet(new HashSet<>());
    private final Set<String> bannedIps = Collections.synchronizedSet(new HashSet<>());

    /**
     * Optional hook called (on the scheduler thread) when a ghost player is
     * permanently removed after the reconnect timeout expires.
     */
    public Consumer<Player> onPermanentRemove = null;

    public PlayerManager(ServerConfig config, EventBus bus) {
        this.config = config;
        this.bus    = bus;
    }

    // ── Connect / Reconnect ───────────────────────────────────────────

    /** Factory method — override to return a custom Player subclass. */
    protected Player createPlayer(String name) {
        return new Player(name);
    }

    /** Fresh join: creates a new Player, registers the handler, fires PLAYER_JOINED. */
    public synchronized Player join(String connId, String name, IClientHandler handler) {
        Player player = createPlayer(name);
        register(connId, player, handler);
        bus.emit(new ServerEvent.Builder(EventType.PLAYER_JOINED).player(player).build());
        return player;
    }

    /**
     * Reconnect using a previously issued token.
     * Cancels the pending removal timer and restores the live connection.
     *
     * @return the Player on success, null if token not found / expired
     */
    public synchronized Player reconnect(String connId, String token, IClientHandler handler) {
        Player player = playersByToken.get(token);
        if (player == null) return null;

        cancelPendingRemoval(player.getId());
        closeStaleConnection(player, connId);

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
                    config.reconnectTimeoutMs, TimeUnit.MILLISECONDS);
            pendingRemoval.put(player.getId(), fut);
            System.out.printf("[PlayerManager] Ghost '%s' — timeout in %dms%n",
                    player.getName(), config.reconnectTimeoutMs);
        } else {
            permanentRemove(player.getId());
        }
    }

    private synchronized void permanentRemove(String playerId) {
        Player player = playersById.remove(playerId);
        if (player == null) return;  // already reconnected

        playersByToken.remove(player.getToken());
        pendingRemoval.remove(playerId);
        System.out.printf("[PlayerManager] Permanently removed '%s'%n", player.getName());

        bus.emit(new ServerEvent.Builder(EventType.PLAYER_LEFT).player(player).build());

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
        sendAndClose(player, OutboundMsg.kicked(reason).toJson());
        permanentRemove(playerId);
        bus.emit(new ServerEvent.Builder(EventType.PLAYER_KICKED)
                .player(player).message(reason).build());
    }

    public synchronized void ban(String playerId, String reason) {
        Player player = playersById.get(playerId);
        if (player != null) {
            bannedIps.add(getHandlerIp(player));
            sendAndClose(player, OutboundMsg.banned(reason).toJson());
            permanentRemove(playerId);
        }
        bannedIds.add(playerId);
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
            if (!e.getKey().equals(excludeConnId)) e.getValue().send(json);
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

    private void cancelPendingRemoval(String playerId) {
        ScheduledFuture<?> fut = pendingRemoval.remove(playerId);
        if (fut != null) fut.cancel(false);
    }

    /** Close the player's old connection if it differs from the new connId. */
    private void closeStaleConnection(Player player, String newConnId) {
        String oldConnId = player.getConnId();
        if (oldConnId == null || oldConnId.equals(newConnId)) return;
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
        System.out.printf("[PlayerManager] Disconnected old connection '%s' for player '%s'%n",
                oldConnId, player.getName());
    }

    /** Send a message to the player and close their connection. */
    private void sendAndClose(Player player, String json) {
        IClientHandler h = handlers.get(player.getConnId());
        if (h != null) {
            h.send(json);
            h.close();
        }
    }

    private String getHandlerIp(Player player) {
        IClientHandler h = handlers.get(player.getConnId());
        return h != null ? h.getRemoteIp() : null;
    }

    public void shutdown() { scheduler.shutdownNow(); }
}

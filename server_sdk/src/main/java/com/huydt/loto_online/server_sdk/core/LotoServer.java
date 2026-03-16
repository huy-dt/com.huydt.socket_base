package com.huydt.loto_online.server_sdk.core;

import com.huydt.loto_online.server_sdk.callback.LotoServerCallback;
import com.huydt.socket_base.server.core.*;
import com.huydt.socket_base.server.event.EventBus;
import com.huydt.socket_base.server.network.MessageDispatcher;

/**
 * Entry point for the Loto Online SDK.
 *
 * <p>Extends {@link SocketBaseServer} and injects Loto-specific managers via
 * the factory method pattern — no changes required to the base SDK.
 *
 * <h3>Minimal usage</h3>
 * <pre>
 * LotoServer server = new LotoServer.Builder().build();
 * new Thread(server::startSafe).start();
 * </pre>
 *
 * <h3>Fully configured</h3>
 * <pre>
 * LotoServer server = new LotoServer.Builder()
 *     .baseConfig(new ServerConfig.Builder()
 *         .transport(TransportMode.WS)
 *         .wsPort(9001)
 *         .adminToken("my-secret")
 *         .build())
 *     .lotoConfig(new LotoConfig.Builder()
 *         .pricePerPage(10_000)
 *         .initialBalance(100_000)
 *         .drawIntervalMs(4000)
 *         .voteThresholdPct(51)
 *         .autoVerifyWin(true)
 *         .autoResetDelayMs(30_000)
 *         .autoStartMs(10_000)
 *         .minPlayers(2)
 *         .build())
 *     .callback(new LotoServerCallback() {
 *         public void onNumberDrawn(String roomId, int n, List&lt;Integer&gt; all) {
 *             System.out.println("Drew: " + n);
 *         }
 *     })
 *     .build();
 *
 * // Create rooms
 * server.getAdmin().createRoom("vip-1", "VIP Room 1", 20);
 *
 * // Start (blocks calling thread)
 * server.startSafe();
 * </pre>
 */
public class LotoServer extends SocketBaseServer {

    private final LotoConfig           lotoConfig;
    private final LotoServerCallback   callback;

    // ── ThreadLocal để truyền lotoConfig vào factory methods ──────
    // super() gọi createPlayerManager/createRoomManager/createDispatcher
    // trước khi this.lotoConfig được gán — dùng ThreadLocal để bridge.
    private static final ThreadLocal<LotoConfig> pendingConfig = new ThreadLocal<>();

    // ── Constructor ───────────────────────────────────────────────

    private LotoServer(Builder b) {
        super(b.baseConfig);          // gọi create* factories
        this.lotoConfig = b.lotoConfig;
        this.callback   = b.callback;
        pendingConfig.remove();        // dọn dẹp sau khi xong
    }

    // ── Factory methods (called in order by SocketBaseServer constructor) ──

    @Override
    protected PlayerManager createPlayerManager(ServerConfig config, EventBus bus) {
        return new LotoPlayerManager(config, bus, pendingConfig.get().initialBalance);
    }

    @Override
    protected RoomManager createRoomManager(ServerConfig config, PlayerManager pm, EventBus bus) {
        return new LotoRoomManager(config, pm, bus, pendingConfig.get());
    }

    @Override
    protected void onServerStarted() {
        System.out.println("[LotoServer] " + lotoConfig);
    }

    // ── Convenience accessor ──────────────────────────────────────

    private LotoDispatcher lotoDispatcher;

    @Override
    protected MessageDispatcher createDispatcher(PlayerManager pm, RoomManager rm,
                                                  EventBus bus, String adminToken) {
        lotoDispatcher = new LotoDispatcher(pm, rm, bus, adminToken, pendingConfig.get());
        return lotoDispatcher;
    }

    public LotoDispatcher getLotoDispatcher() { return lotoDispatcher; }

    // ── Builder ───────────────────────────────────────────────────

    public static class Builder {
        private ServerConfig         baseConfig = new ServerConfig.Builder()
                                                        .transport(TransportMode.WS)
                                                        .wsPort(9000)
                                                        .autoCreateDefaultRoom(false)
                                                        .build();
        private LotoConfig           lotoConfig = new LotoConfig.Builder().build();
        private LotoServerCallback   callback   = null;

        /** Base network/auth config. */
        public Builder baseConfig(ServerConfig config) { this.baseConfig = config; return this; }

        /** Loto game config (prices, timers, rules). */
        public Builder lotoConfig(LotoConfig config)   { this.lotoConfig = config; return this; }

        /** Game event callback. */
        public Builder callback(LotoServerCallback cb) { this.callback = cb; return this; }

        public LotoServer build() {
            pendingConfig.set(lotoConfig);  // set trước khi super() chạy factory methods
            return new LotoServer(this);
        }
    }
}

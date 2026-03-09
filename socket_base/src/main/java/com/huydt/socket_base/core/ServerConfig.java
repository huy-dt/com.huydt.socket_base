package com.huydt.socket_base.core;

import java.util.UUID;

/**
 * Immutable configuration for SocketBaseServer.
 *
 * <pre>
 * ServerConfig config = new ServerConfig.Builder()
 *     .port(9000)
 *     .wsPort(9001)
 *     .transport(TransportMode.BOTH)
 *     .adminToken("my-secret")
 *     .reconnectTimeoutMs(30_000)
 *     .build();
 * </pre>
 */
public final class ServerConfig {

    // ── Network ───────────────────────────────────────────────────────
    public final int           tcpPort;
    public final int           wsPort;           // 0 = WS disabled
    public final TransportMode transport;

    // ── Player ────────────────────────────────────────────────────────
    public final int    reconnectTimeoutMs;       // ms a ghost player keeps their slot
    public final int    maxPlayersPerRoom;         // 0 = unlimited

    // ── Room ──────────────────────────────────────────────────────────
    public final int    maxRooms;                 // 0 = unlimited
    public final boolean autoCreateDefaultRoom;   // create room "default" on start

    // ── Admin ─────────────────────────────────────────────────────────
    public final String adminToken;               // secret; auto-generated if null

    // ── Persistence ───────────────────────────────────────────────────
    public final String persistPath;              // null = disabled

    private ServerConfig(Builder b) {
        this.tcpPort               = b.tcpPort;
        this.wsPort                = b.wsPort;
        this.transport             = b.transport;
        this.reconnectTimeoutMs    = b.reconnectTimeoutMs;
        this.maxPlayersPerRoom     = b.maxPlayersPerRoom;
        this.maxRooms              = b.maxRooms;
        this.autoCreateDefaultRoom = b.autoCreateDefaultRoom;
        this.adminToken            = (b.adminToken != null && !b.adminToken.isEmpty())
                                        ? b.adminToken
                                        : UUID.randomUUID().toString();
        this.persistPath           = b.persistPath;
    }

    @Override
    public String toString() {
        return String.format(
            "ServerConfig{tcp=%d, ws=%d, transport=%s, reconnectTimeout=%dms, " +
            "maxPlayers=%s, maxRooms=%s, persist=%s}",
            tcpPort, wsPort, transport, reconnectTimeoutMs,
            maxPlayersPerRoom == 0 ? "unlimited" : String.valueOf(maxPlayersPerRoom),
            maxRooms == 0 ? "unlimited" : String.valueOf(maxRooms),
            persistPath != null ? persistPath : "off");
    }

    // ─── Builder ──────────────────────────────────────────────────────

    public static class Builder {
        private int           tcpPort               = 9000;
        private int           wsPort                = tcpPort + 1;
        private TransportMode transport             = TransportMode.WS;
        private int           reconnectTimeoutMs    = 30_000;
        private int           maxPlayersPerRoom     = 0;
        private int           maxRooms              = 0;
        private boolean       autoCreateDefaultRoom = true;
        private String        adminToken            = null;
        private String        persistPath           = null;

        /** TCP port. Default: 9000 */
        public Builder port(int port)            { this.tcpPort = validated(port, 1, 65535, "port"); return this; }

        /** WebSocket port. 0 = disabled. */
        public Builder wsPort(int port)          { this.wsPort = port; return this; }

        /**
         * Transport mode shortcuts:
         *   --tcp  → TransportMode.TCP
         *   --ws   → TransportMode.WS   (wsPort defaults to tcpPort+1 if not set)
         *   --both → TransportMode.BOTH
         */
        public Builder transport(TransportMode t) {
            this.transport = t != null ? t : TransportMode.TCP;
            return this;
        }

        /** Milliseconds a disconnected player keeps their slot. Default: 30000 */
        public Builder reconnectTimeoutMs(int ms) { this.reconnectTimeoutMs = ms; return this; }

        /** Max players per room. 0 = unlimited. */
        public Builder maxPlayersPerRoom(int n)  { this.maxPlayersPerRoom = n; return this; }

        /** Max concurrent rooms. 0 = unlimited. */
        public Builder maxRooms(int n)           { this.maxRooms = n; return this; }

        /** If true, a room named "default" is created automatically on server start. Default: true */
        public Builder autoCreateDefaultRoom(boolean v) { this.autoCreateDefaultRoom = v; return this; }

        /** Admin secret token. Auto-generated UUID if not provided. */
        public Builder adminToken(String token)  { this.adminToken = token; return this; }

        /** Path to JSON persistence file. null = no persistence. */
        public Builder persistPath(String path)  { this.persistPath = path; return this; }

        public ServerConfig build() {
            // Resolve wsPort defaults here, after all args have been parsed,
            // so --port and --ws-port order doesn't matter on the CLI.
            // if (transport == TransportMode.TCP) {
            //     wsPort = 0;                                    // TCP-only: WS disabled
            // } else if (wsPort == 0) {
            //     wsPort = tcpPort + 1;                          // WS / BOTH: default to tcp+1
            // }
            return new ServerConfig(this);
        }

        private int validated(int val, int min, int max, String name) {
            if (val < min || val > max)
                throw new IllegalArgumentException(name + " must be " + min + "–" + max);
            return val;
        }
    }
}

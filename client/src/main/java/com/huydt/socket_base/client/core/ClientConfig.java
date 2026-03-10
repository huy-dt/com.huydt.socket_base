package com.huydt.socket_base.client.core;

/**
 * Immutable configuration for {@link SocketBaseClient}.
 *
 * <h3>Transport modes</h3>
 * <ul>
 *   <li><b>WS / WSS</b>  — WebSocket (default). Accepts full URL or host+port.</li>
 *   <li><b>TCP / TCP_SSL</b> — Raw TCP socket with optional TLS.</li>
 *   <li><b>UDP / UDP_SSL</b> — UDP socket with optional DTLS.</li>
 * </ul>
 *
 * <h3>Examples</h3>
 * <pre>
 * // WebSocket — URL without port (port inferred from scheme)
 * ClientConfig ws = new ClientConfig.Builder()
 *     .url("wss://game.example.com/ws")
 *     .build();
 *
 * // TCP + SSL
 * ClientConfig tcp = new ClientConfig.Builder()
 *     .host("game.example.com").port(9002)
 *     .protocol(ClientConfig.Protocol.TCP_SSL)
 *     .build();
 *
 * // UDP
 * ClientConfig udp = new ClientConfig.Builder()
 *     .host("game.example.com").port(9003)
 *     .protocol(ClientConfig.Protocol.UDP)
 *     .build();
 * </pre>
 */
public final class ClientConfig {

    // ── Protocol enum ─────────────────────────────────────────────────

    public enum Protocol {
        /** Plain WebSocket  (ws://)  */
        WS,
        /** Secure WebSocket (wss://) */
        WSS,
        /** Plain TCP socket           */
        TCP,
        /** TCP + TLS/SSL              */
        TCP_SSL,
        /** Plain UDP socket           */
        UDP,
        /** UDP + DTLS                 */
        UDP_SSL;

        public boolean isSsl()       { return this == WSS || this == TCP_SSL || this == UDP_SSL; }
        public boolean isWebSocket() { return this == WS  || this == WSS; }
        public boolean isTcp()       { return this == TCP || this == TCP_SSL; }
        public boolean isUdp()       { return this == UDP || this == UDP_SSL; }
    }

    // ── Fields ────────────────────────────────────────────────────────

    public final Protocol protocol;
    public final String   host;
    public final int      port;       // -1 = use scheme default
    public final String   path;       // WS path, e.g. "/ws" (may be null)

    /** Timeout waiting for connection in ms. 0 = no timeout. */
    public final int connectTimeoutMs;

    /** Reconnect window in ms. 0 = no auto-reconnect. */
    public final int reconnectTimeoutMs;

    // ── Legacy compat fields (read-only aliases) ───────────────────────

    /** @deprecated Use {@link #protocol} */
    @Deprecated
    public final boolean useSsl;

    private ClientConfig(Builder b) {
        this.protocol           = b.protocol;
        this.host               = b.host;
        this.port               = b.port;
        this.path               = b.path;
        this.connectTimeoutMs   = b.connectTimeoutMs;
        this.reconnectTimeoutMs = b.reconnectTimeoutMs;
        this.useSsl             = b.protocol.isSsl();
    }

    // ── URI / address helpers ─────────────────────────────────────────

    /**
     * Returns the effective port, falling back to scheme default when port == -1.
     * ws=80, wss=443, tcp=9001, tcp_ssl=9002, udp/udp_ssl=9001
     */
    public int effectivePort() {
        if (port > 0) return port;
        switch (protocol) {
            case WS:      return 80;
            case WSS:     return 443;
            case TCP_SSL: return 9002;
            default:      return 9001;
        }
    }

    /**
     * WebSocket URI — only valid for WS/WSS.
     * Port is omitted when it matches the scheme default (80 / 443).
     */
    public String wsUri() {
        if (!protocol.isWebSocket())
            throw new IllegalStateException("wsUri() called on non-WebSocket protocol: " + protocol);
        String scheme = protocol == Protocol.WSS ? "wss" : "ws";
        int effPort = effectivePort();
        boolean isDefault = (protocol == Protocol.WS  && effPort == 80)
                         || (protocol == Protocol.WSS && effPort == 443);
        String base = scheme + "://" + host + (isDefault ? "" : ":" + effPort);
        return path != null ? base + path : base;
    }

    /** Human-readable address for TCP / UDP transport. */
    public String tcpAddress() { return host + ":" + effectivePort(); }

    @Override
    public String toString() {
        if (protocol.isWebSocket())
            return String.format("ClientConfig{uri=%s, connectTimeout=%dms}", wsUri(), connectTimeoutMs);
        return String.format("ClientConfig{protocol=%s, address=%s, connectTimeout=%dms}",
                protocol, tcpAddress(), connectTimeoutMs);
    }

    // ── Builder ───────────────────────────────────────────────────────

    public static class Builder {
        private Protocol protocol           = Protocol.WS;
        private String   host               = "localhost";
        private int      port               = -1;
        private String   path               = null;
        private int      connectTimeoutMs   = 5_000;
        private int      reconnectTimeoutMs = 0;

        public Builder protocol(Protocol p)       { this.protocol = p; return this; }
        public Builder host(String h)             { this.host = h; return this; }
        public Builder port(int p)                { this.port = p; return this; }
        public Builder path(String p)             { this.path = p; return this; }
        public Builder connectTimeoutMs(int ms)   { this.connectTimeoutMs = ms; return this; }
        public Builder reconnectTimeoutMs(int ms) { this.reconnectTimeoutMs = ms; return this; }

        /**
         * Toggle SSL — switches WS↔WSS, TCP↔TCP_SSL, UDP↔UDP_SSL based on current protocol.
         */
        public Builder useSsl(boolean ssl) {
            if (ssl) {
                switch (protocol) {
                    case WS:  protocol = Protocol.WSS;     break;
                    case TCP: protocol = Protocol.TCP_SSL; break;
                    case UDP: protocol = Protocol.UDP_SSL; break;
                    default: break;
                }
            } else {
                switch (protocol) {
                    case WSS:     protocol = Protocol.WS;  break;
                    case TCP_SSL: protocol = Protocol.TCP; break;
                    case UDP_SSL: protocol = Protocol.UDP; break;
                    default: break;
                }
            }
            return this;
        }

        /**
         * Parse a full URL — sets protocol, host, port (-1 if absent), and path.
         *
         * <p>Supported schemes: {@code ws}, {@code wss}, {@code tcp}, {@code tcp+ssl},
         * {@code udp}, {@code udp+ssl}.</p>
         *
         * <pre>
         * .url("wss://game.example.com/ws")     // port → -1  → effective 443
         * .url("ws://localhost:9001")            // port → 9001
         * .url("tcp+ssl://game.example.com:9002")
         * </pre>
         */
        public Builder url(String rawUrl) {
            if (rawUrl == null || rawUrl.isBlank())
                throw new IllegalArgumentException("URL must not be blank");

            String url = rawUrl.trim();
            int schemeEnd = url.indexOf("://");
            if (schemeEnd < 0)
                throw new IllegalArgumentException("URL has no scheme (://): " + rawUrl);

            String scheme = url.substring(0, schemeEnd).toLowerCase();
            String rest   = url.substring(schemeEnd + 3);

            switch (scheme) {
                case "ws":      this.protocol = Protocol.WS;      break;
                case "wss":     this.protocol = Protocol.WSS;     break;
                case "tcp":     this.protocol = Protocol.TCP;     break;
                case "tcp+ssl":
                case "tcpssl":  this.protocol = Protocol.TCP_SSL; break;
                case "udp":     this.protocol = Protocol.UDP;     break;
                case "udp+ssl":
                case "udpssl":  this.protocol = Protocol.UDP_SSL; break;
                default:        throw new IllegalArgumentException("Unknown scheme: " + scheme);
            }

            // Separate host[:port] from /path
            int slashIdx = rest.indexOf('/');
            String hostPort = slashIdx >= 0 ? rest.substring(0, slashIdx) : rest;
            this.path = slashIdx >= 0 ? rest.substring(slashIdx) : null;

            // IPv6 literal [::1] or [::1]:port
            if (hostPort.startsWith("[")) {
                int close = hostPort.indexOf(']');
                if (close < 0) throw new IllegalArgumentException("Unclosed '[' in URL: " + rawUrl);
                this.host = hostPort.substring(1, close);
                String after = hostPort.substring(close + 1);
                this.port = after.startsWith(":") ? Integer.parseInt(after.substring(1)) : -1;
            } else {
                int colon = hostPort.lastIndexOf(':');
                if (colon >= 0) {
                    this.host = hostPort.substring(0, colon);
                    this.port = Integer.parseInt(hostPort.substring(colon + 1));
                } else {
                    this.host = hostPort;
                    this.port = -1;   // infer from scheme
                }
            }
            return this;
        }

        public ClientConfig build() {
            if (host == null || host.isBlank())
                throw new IllegalArgumentException("host must not be blank");
            return new ClientConfig(this);
        }
    }
}
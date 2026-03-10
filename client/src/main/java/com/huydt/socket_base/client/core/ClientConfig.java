package com.huydt.socket_base.client.core;

/**
 * Immutable configuration for {@link SocketBaseClient}.
 *
 * <pre>
 * ClientConfig config = new ClientConfig.Builder()
 *     .host("localhost")
 *     .port(9001)
 *     .connectTimeoutMs(5_000)
 *     .build();
 * </pre>
 */
public final class ClientConfig {

    public final String  host;
    public final int     port;
    public final boolean useSsl;

    /** Timeout waiting for connection in ms. 0 = no timeout. */
    public final int connectTimeoutMs;

    /** Reconnect window in ms — mirrors ServerConfig.reconnectTimeoutMs. 0 = no auto-reconnect. */
    public final int reconnectTimeoutMs;

    private ClientConfig(Builder b) {
        this.host                = b.host;
        this.port                = b.port;
        this.useSsl              = b.useSsl;
        this.connectTimeoutMs    = b.connectTimeoutMs;
        this.reconnectTimeoutMs  = b.reconnectTimeoutMs;
    }

    public String wsUri() {
        return (useSsl ? "wss" : "ws") + "://" + host + ":" + port;
    }

    @Override
    public String toString() {
        return String.format("ClientConfig{uri=%s, connectTimeout=%dms}", wsUri(), connectTimeoutMs);
    }

    // ── Builder ───────────────────────────────────────────────────────

    public static class Builder {
        private String  host                = "localhost";
        private int     port                = 9001;
        private boolean useSsl              = false;
        private int     connectTimeoutMs    = 5_000;
        private int     reconnectTimeoutMs  = 0;

        public Builder host(String host)                { this.host = host; return this; }
        public Builder port(int port)                   { this.port = port; return this; }
        public Builder useSsl(boolean ssl)              { this.useSsl = ssl; return this; }
        public Builder connectTimeoutMs(int ms)         { this.connectTimeoutMs = ms; return this; }
        public Builder reconnectTimeoutMs(int ms)       { this.reconnectTimeoutMs = ms; return this; }

        public ClientConfig build() { return new ClientConfig(this); }
    }
}

package com.huydt.socket_client_base.core;

/**
 * Immutable configuration for {@link SocketBaseClient}.
 *
 * <pre>
 * ClientConfig config = new ClientConfig.Builder()
 *     .host("localhost")
 *     .port(9001)
 *     .connectTimeoutMs(5000)
 *     .build();
 * </pre>
 */
public final class ClientConfig {

    public final String  host;
    public final int     port;
    public final boolean useSsl;

    /** Timeout waiting for connection in ms. 0 = no timeout. */
    public final int     connectTimeoutMs;

    private ClientConfig(Builder b) {
        this.host             = b.host;
        this.port             = b.port;
        this.useSsl           = b.useSsl;
        this.connectTimeoutMs = b.connectTimeoutMs;
    }

    public String wsUri() {
        return (useSsl ? "wss" : "ws") + "://" + host + ":" + port;
    }

    @Override
    public String toString() {
        return String.format("ClientConfig{uri=%s}", wsUri());
    }

    // ── Builder ───────────────────────────────────────────────────────

    public static class Builder {
        private String  host             = "localhost";
        private int     port             = 9001;
        private boolean useSsl           = false;
        private int     connectTimeoutMs = 5_000;

        public Builder host(String host)           { this.host = host; return this; }
        public Builder port(int port)              { this.port = port; return this; }
        public Builder useSsl(boolean ssl)         { this.useSsl = ssl; return this; }
        public Builder connectTimeoutMs(int ms)    { this.connectTimeoutMs = ms; return this; }

        public ClientConfig build() { return new ClientConfig(this); }
    }
}
package com.huydt.loto_online.server_sdk.core;

/**
 * Loto-specific configuration — passed alongside the base
 * {@link com.huydt.socket_base.server.core.ServerConfig}.
 *
 * <pre>
 * LotoConfig loto = new LotoConfig.Builder()
 *     .pricePerPage(10_000)
 *     .initialBalance(0)
 *     .drawIntervalMs(5000)
 *     .maxPagesPerBuy(10)
 *     .voteThresholdPct(51)
 *     .autoVerifyWin(true)
 *     .autoResetDelayMs(30_000)
 *     .autoStartMs(10_000)
 *     .minPlayers(2)
 *     .build();
 * </pre>
 */
public final class LotoConfig {

    public final long    pricePerPage;
    public final long    initialBalance;
    public final int     drawIntervalMs;
    public final int     maxPagesPerBuy;
    public final int     voteThresholdPct;
    public final boolean autoVerifyWin;
    public final int     autoResetDelayMs;
    public final int     autoStartMs;
    public final int     minPlayers;
    public final int     reconnectTimeoutMs;

    private LotoConfig(Builder b) {
        this.pricePerPage       = b.pricePerPage;
        this.initialBalance     = b.initialBalance;
        this.drawIntervalMs     = b.drawIntervalMs;
        this.maxPagesPerBuy     = b.maxPagesPerBuy;
        this.voteThresholdPct   = b.voteThresholdPct;
        this.autoVerifyWin      = b.autoVerifyWin;
        this.autoResetDelayMs   = b.autoResetDelayMs;
        this.autoStartMs        = b.autoStartMs;
        this.minPlayers         = b.minPlayers;
        this.reconnectTimeoutMs = b.reconnectTimeoutMs;
    }

    @Override
    public String toString() {
        return String.format(
            "LotoConfig{price=%d, balance=%d, drawInterval=%dms, maxPages=%d, " +
            "voteThreshold=%d%%, autoVerify=%b, autoReset=%dms, autoStart=%dms, minPlayers=%d}",
            pricePerPage, initialBalance, drawIntervalMs, maxPagesPerBuy,
            voteThresholdPct, autoVerifyWin, autoResetDelayMs, autoStartMs, minPlayers);
    }

    public static class Builder {
        private long    pricePerPage       = 10_000;
        private long    initialBalance     = 0;
        private int     drawIntervalMs     = 5_000;
        private int     maxPagesPerBuy     = 10;
        private int     voteThresholdPct   = 51;
        private boolean autoVerifyWin      = false;
        private int     autoResetDelayMs   = 0;
        private int     autoStartMs        = 0;
        private int     minPlayers         = 1;
        private int     reconnectTimeoutMs = 30_000;

        public Builder pricePerPage(long v)       { pricePerPage = v;       return this; }
        public Builder initialBalance(long v)     { initialBalance = v;     return this; }
        public Builder drawIntervalMs(int v)      { drawIntervalMs = v;     return this; }
        public Builder maxPagesPerBuy(int v)      { maxPagesPerBuy = v;     return this; }
        public Builder voteThresholdPct(int v)    { voteThresholdPct = v;   return this; }
        public Builder autoVerifyWin(boolean v)   { autoVerifyWin = v;      return this; }
        public Builder autoResetDelayMs(int v)    { autoResetDelayMs = v;   return this; }
        public Builder autoStartMs(int v)         { autoStartMs = v;        return this; }
        public Builder minPlayers(int v)          { minPlayers = v;         return this; }
        public Builder reconnectTimeoutMs(int v)  { reconnectTimeoutMs = v; return this; }

        public LotoConfig build() { return new LotoConfig(this); }
    }
}

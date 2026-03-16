package com.huydt.loto_online.server_sdk.model;

/**
 * A bot player — same as {@link LotoPlayer} but flagged as a bot.
 * AI logic lives in {@link com.huydt.loto_online.server_sdk.core.BotManager}.
 */
public class BotPlayer extends LotoPlayer {

    private final int maxPages;

    public BotPlayer(String name, long initialBalance, int maxPages) {
        super(name, initialBalance);
        this.maxPages = Math.max(1, maxPages);
    }

    public int getMaxPages() { return maxPages; }

    @Override
    public boolean isBot() { return true; }
}

package com.huydt.loto_online.server_sdk.model;

import com.huydt.socket_base.server.model.Player;
import org.json.JSONArray;
import org.json.JSONObject;

import java.util.ArrayList;
import java.util.Collections;
import java.util.List;
import java.util.UUID;

/**
 * Loto-specific player — extends socket_base {@link Player}.
 *
 * <p>Adds:
 * <ul>
 *   <li>{@code balance}      — current wallet balance</li>
 *   <li>{@code pages}        — loto pages bought this round</li>
 *   <li>{@code transactions} — full ledger (private, owner-only)</li>
 * </ul>
 *
 * <h3>Serialization contract</h3>
 * <ul>
 *   <li>{@link #toJson()}       — full private snapshot (balance + transactions) → WELCOME only</li>
 *   <li>{@link #toPublicJson()} — public snapshot (pageCount, no balance) → broadcast</li>
 * </ul>
 */
public class LotoPlayer extends Player {

    private long               balance;
    private final List<LotoPage>    pages        = new ArrayList<>();
    private final List<Transaction> transactions = new ArrayList<>();

    public LotoPlayer(String name, long initialBalance) {
        super(name);
        this.balance = initialBalance;
    }

    // ── Accessors ─────────────────────────────────────────────────

    public long              getBalance()      { return balance; }
    public List<LotoPage>    getPages()        { return Collections.unmodifiableList(pages); }
    public List<Transaction> getTransactions() { return Collections.unmodifiableList(transactions); }

    public LotoPage getPageById(int pageId) {
        return pages.stream().filter(p -> p.getId() == pageId).findFirst().orElse(null);
    }

    /** Returns true if this player is a bot. Overridden by {@link com.huydt.loto_online.server_sdk.model.BotPlayer}. */
    public boolean isBot() { return false; }

    // ── Money ops ─────────────────────────────────────────────────

    /** Deducts amount; returns false if balance insufficient. */
    public boolean deduct(long amount, String note) {
        if (balance < amount) return false;
        balance -= amount;
        transactions.add(new Transaction(Transaction.Type.BUY_PAGE, -amount, balance, note));
        return true;
    }

    public void addPrize(long amount, String note) {
        balance += amount;
        transactions.add(new Transaction(Transaction.Type.WIN_PRIZE, amount, balance, note));
    }

    public void topUp(long amount, String note) {
        balance += amount;
        transactions.add(new Transaction(Transaction.Type.TOPUP, amount, balance, note));
    }

    public void refund(long amount, String note) {
        balance += amount;
        transactions.add(new Transaction(Transaction.Type.REFUND, amount, balance, note));
    }

    // ── Page ops ──────────────────────────────────────────────────

    public void addPages(List<LotoPage> newPages)  { pages.addAll(newPages); }
    public void clearPages()                        { pages.clear(); }

    // ── Serialization ─────────────────────────────────────────────

    /**
     * Full private snapshot — send ONLY to this player (WELCOME / RECONNECTED).
     * Includes balance, transactions.
     */
    @Override
    public JSONObject toJson() {
        JSONObject j = super.toJson();
        j.put("balance", balance);
        j.put("pageCount", pages.size());

        JSONArray txArr = new JSONArray();
        for (Transaction tx : transactions) {
            JSONObject t = new JSONObject();
            t.put("timestamp",    tx.getTimestamp());
            t.put("type",         tx.getType().name());
            t.put("amount",       tx.getAmount());
            t.put("balanceAfter", tx.getBalanceAfter());
            t.put("note",         tx.getNote());
            txArr.put(t);
        }
        j.put("transactions", txArr);
        return j;
    }

    /**
     * Public snapshot — safe to broadcast to all players in a room.
     * No balance, no transactions — only pageCount.
     */
    @Override
    public JSONObject toPublicJson() {
        JSONObject j = super.toPublicJson(); // strips token
        j.remove("transactions");
        j.put("pageCount", pages.size());
        // balance intentionally omitted
        return j;
    }

    @Override
    public void fromJson(JSONObject j) {
        super.fromJson(j);
        this.balance = j.optLong("balance", 0);
    }

    @Override
    public String toString() {
        return "LotoPlayer{id=" + getId() + ", name=" + getName()
                + ", balance=" + balance + ", pages=" + pages.size() + "}";
    }
}

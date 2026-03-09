package com.huydt.socket_base.model;

import org.json.JSONObject;
import java.io.Serializable;
import java.util.UUID;

/**
 * Base class for all SDK models (Player, Room, etc.).
 *
 * <h3>Serialization contract</h3>
 * <p>{@link #toJson()} always returns the <b>complete</b> object — base fields plus
 * every field added by subclasses.  Subclasses must call {@code super.toJson()} and
 * add their own fields to the returned object:
 *
 * <pre>
 * public class LotoPlayer extends Player {
 *     public long  balance = 0;
 *     public int   pageCount = 0;
 *
 *     &#64;Override
 *     public JSONObject toJson() {
 *         JSONObject j = super.toJson();   // ← contains id, name, roomId, connected, …
 *         j.put("balance",   balance);
 *         j.put("pageCount", pageCount);
 *         return j;                        // ← one complete object
 *     }
 * }
 * </pre>
 *
 * <p>{@link #toPublicJson()} (defined in {@link Player} / {@link Room}) follows the
 * same contract — subclasses override it to add fields that are safe to send to
 * all other players (no tokens, no private data).
 *
 * <h3>fromJson contract</h3>
 * <pre>
 *     &#64;Override
 *     public void fromJson(JSONObject j) {
 *         super.fromJson(j);
 *         this.balance   = j.optLong("balance", 0);
 *         this.pageCount = j.optInt("pageCount", 0);
 *     }
 * </pre>
 */
public abstract class BaseModel implements Serializable {

    private static final long serialVersionUID = 1L;

    private String id;
    private long   createdAt;

    protected BaseModel() {
        this.id        = UUID.randomUUID().toString().replace("-", "").substring(0, 12);
        this.createdAt = System.currentTimeMillis();
    }

    /** Restore with explicit id (e.g. from persistence). */
    protected BaseModel(String id) {
        this.id        = id;
        this.createdAt = System.currentTimeMillis();
    }

    // ── Accessors ─────────────────────────────────────────────────────

    public String getId()        { return id; }
    public long   getCreatedAt() { return createdAt; }

    /** Override id (e.g. after loading from storage). */
    protected void setId(String id) { this.id = id; }

    // ── Serialization ─────────────────────────────────────────────────

    /**
     * Converts this model to a JSONObject.
     * Subclasses should call {@code super.toJson()} and add their own fields.
     */
    public JSONObject toJson() {
        JSONObject j = new JSONObject();
        j.put("id",        id);
        j.put("createdAt", createdAt);
        return j;
    }

    /**
     * Populates fields from a JSONObject.
     * Subclasses should call {@code super.fromJson(j)} first, then read their own fields.
     */
    public void fromJson(JSONObject j) {
        if (j.has("id"))        this.id        = j.getString("id");
        if (j.has("createdAt")) this.createdAt = j.optLong("createdAt", createdAt);
    }

    // ── equals / hashCode ─────────────────────────────────────────────

    @Override
    public boolean equals(Object o) {
        if (this == o) return true;
        if (!(o instanceof BaseModel)) return false;
        return id.equals(((BaseModel) o).id);
    }

    @Override
    public int hashCode() { return id.hashCode(); }

    @Override
    public String toString() {
        return getClass().getSimpleName() + "{id=" + id + "}";
    }
}

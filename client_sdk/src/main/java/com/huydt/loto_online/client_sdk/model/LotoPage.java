package com.huydt.loto_online.client_sdk.model;

import org.json.JSONArray;
import org.json.JSONObject;

import java.util.ArrayList;
import java.util.Collections;
import java.util.List;

/**
 * Client-side representation of one Loto page (tờ loto).
 *
 * <p>Grid is 9 rows × 9 columns. Each cell is either a number (1–90) or null (empty).
 * Marked cells ({@code marked[][]}) are set locally when a drawn number matches.
 *
 * <h3>Usage</h3>
 * <pre>
 * // Build from server JSON
 * LotoPage page = LotoPage.fromJson(pageJson);
 *
 * // Mark a drawn number
 * page.mark(42);
 *
 * // Check if any row is complete
 * boolean won = page.hasWinningRow();
 * </pre>
 */
public class LotoPage {

    public static final int ROWS = 9;
    public static final int COLS = 9;

    public final int              id;
    public final Integer[][]      grid;    // null = empty cell
    public final boolean[][]      marked;  // true = drawn + matched

    private LotoPage(int id, Integer[][] grid) {
        this.id     = id;
        this.grid   = grid;
        this.marked = new boolean[ROWS][COLS];
    }

    // ── Factory ───────────────────────────────────────────────────────

    /**
     * Build from PAGES_ASSIGNED / WELCOME page JSON:
     * { "id": 1, "page": [[null,5,null,...], ...] }
     */
    public static LotoPage fromJson(JSONObject j) {
        int id = j.optInt("id", 0);
        Integer[][] grid = new Integer[ROWS][COLS];

        JSONArray rows = j.optJSONArray("page");
        if (rows != null) {
            for (int r = 0; r < Math.min(rows.length(), ROWS); r++) {
                JSONArray row = rows.optJSONArray(r);
                if (row == null) continue;
                for (int c = 0; c < Math.min(row.length(), COLS); c++) {
                    Object val = row.opt(c);
                    grid[r][c] = (val instanceof Integer) ? (Integer) val
                               : (val instanceof Number)  ? ((Number) val).intValue()
                               : null;
                }
            }
        }
        return new LotoPage(id, grid);
    }

    // ── Game logic ────────────────────────────────────────────────────

    /**
     * Mark this number on the grid.
     * @return true if the number was found on this page
     */
    public boolean mark(int number) {
        boolean found = false;
        for (int r = 0; r < ROWS; r++) {
            for (int c = 0; c < COLS; c++) {
                if (grid[r][c] != null && grid[r][c] == number) {
                    marked[r][c] = true;
                    found = true;
                }
            }
        }
        return found;
    }

    /**
     * Marks all numbers in the list (used on reconnect to catch up).
     */
    public void markAll(List<Integer> drawnNumbers) {
        for (int n : drawnNumbers) mark(n);
    }

    /**
     * Returns true if ALL non-null cells in at least one row are marked.
     */
    public boolean hasWinningRow() {
        for (int r = 0; r < ROWS; r++) {
            if (isRowComplete(r)) return true;
        }
        return false;
    }

    public boolean isRowComplete(int row) {
        boolean hasCell = false;
        for (int c = 0; c < COLS; c++) {
            if (grid[row][c] != null) {
                hasCell = true;
                if (!marked[row][c]) return false;
            }
        }
        return hasCell;
    }

    /**
     * Numbers on this page that are not yet marked.
     */
    public List<Integer> getRemainingNumbers() {
        List<Integer> remaining = new ArrayList<>();
        for (int r = 0; r < ROWS; r++)
            for (int c = 0; c < COLS; c++)
                if (grid[r][c] != null && !marked[r][c])
                    remaining.add(grid[r][c]);
        return Collections.unmodifiableList(remaining);
    }

    /**
     * Replace the grid from server JSON (PAGE_CHANGED).
     */
    public void updateGrid(JSONArray pageJson) {
        // Reset marks
        for (boolean[] row : marked) java.util.Arrays.fill(row, false);
        if (pageJson == null) return;
        for (int r = 0; r < Math.min(pageJson.length(), ROWS); r++) {
            JSONArray row = pageJson.optJSONArray(r);
            if (row == null) continue;
            for (int c = 0; c < Math.min(row.length(), COLS); c++) {
                Object val = row.opt(c);
                grid[r][c] = (val instanceof Integer) ? (Integer) val
                           : (val instanceof Number)  ? ((Number) val).intValue()
                           : null;
            }
        }
    }

    @Override
    public String toString() {
        return "LotoPage{id=" + id + ", won=" + hasWinningRow() + "}";
    }
}

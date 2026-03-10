package com.huydt.loto_server_sdk.model;

import com.huydt.socket_base.server.model.Player;
import org.json.JSONArray;
import org.json.JSONObject;

import java.util.ArrayList;
import java.util.Collections;
import java.util.List;

/**
 * Loto-specific player.
 *
 * Extra fields:
 *   money — wallet balance (default 10_000)
 *   pages — list of LotoPage tickets owned by this player
 */
public class LotoPlayer extends Player {

    public long money = 10_000;
    private final List<LotoPage> pages = new ArrayList<>();

    public LotoPlayer(String name) {
        super(name);
    }

    // ------------------------------------------------------------------ pages

    public List<LotoPage> getPages() {
        return Collections.unmodifiableList(pages);
    }

    public int getPageCount() {
        return pages.size();
    }

    public void addPage(LotoPage page) {
        pages.add(page);
    }

    public void clearPages() {
        pages.clear();
    }

    // ---------------------------------------------------------------- toJson
    // Used for: WELCOME, RECONNECTED, persistence

    @Override
    public JSONObject toJson() {
        JSONObject j = super.toJson();
        j.put("money", money);

        JSONArray arr = new JSONArray();
        for (LotoPage p : pages) arr.put(p.toJson());
        j.put("pages", arr);

        return j;
    }

    // ---------------------------------------------------------- toPublicJson
    // Used for: PLAYER_JOINED broadcast, ROOM_SNAPSHOT, PLAYER_UPDATE
    // Exposes pageCount only — money stays private

    @Override
    public JSONObject toPublicJson() {
        JSONObject j = super.toPublicJson();
        j.put("pageCount", pages.size());
        return j;
    }

    // ------------------------------------------------------------ fromJson
    // Used for persistence restore

    @Override
    public void fromJson(JSONObject j) {
        super.fromJson(j);
        this.money = j.optLong("money", 10_000);

        pages.clear();
        JSONArray arr = j.optJSONArray("pages");
        if (arr != null) {
            for (int i = 0; i < arr.length(); i++) {
                pages.add(LotoPage.fromJson(arr.getJSONObject(i)));
            }
        }
    }
}

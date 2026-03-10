package com.huydt.loto_client_sdk.model;

import com.huydt.socket_base.client.model.PlayerInfo;
import com.huydt.socket_base.client.model.RoomInfo;
import org.json.JSONArray;
import org.json.JSONObject;

import java.util.ArrayList;
import java.util.List;

/**
 * Client-side mirror of {@code LotoRoom}.
 */
public class LotoRoomInfo extends RoomInfo {

    public long jackpot       = 0;
    public long pricePerPage  = 0;
    public long timeAutoReset = 0;
    public long timeAutoStart = 0;
    public int  round         = 0;
    public final List<Integer> drawnNumbers = new ArrayList<>();

    @Override
    public void mergeRoom(JSONObject j) {
        super.mergeRoom(j);
        if (j.has("jackpot"))       this.jackpot       = j.optLong("jackpot",       0);
        if (j.has("pricePerPage"))  this.pricePerPage  = j.optLong("pricePerPage",  0);
        if (j.has("timeAutoReset")) this.timeAutoReset = j.optLong("timeAutoReset", 0);
        if (j.has("timeAutoStart")) this.timeAutoStart = j.optLong("timeAutoStart", 0);
        if (j.has("round"))         this.round         = j.optInt("round",          0);
        if (j.has("drawnNumbers")) {
            JSONArray arr = j.optJSONArray("drawnNumbers");
            if (arr != null) {
                drawnNumbers.clear();
                for (int i = 0; i < arr.length(); i++) drawnNumbers.add(arr.optInt(i));
            }
        }
    }

    @Override
    protected PlayerInfo createPlayer(JSONObject j) {
        return new LotoPlayerInfo(j);
    }
}

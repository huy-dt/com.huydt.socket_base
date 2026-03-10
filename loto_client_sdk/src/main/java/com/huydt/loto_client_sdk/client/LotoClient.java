package com.huydt.loto_client_sdk.client;

import com.huydt.loto_client_sdk.model.LotoPlayerInfo;
import com.huydt.loto_client_sdk.model.LotoRoomInfo;
import com.huydt.socket_base.client.core.SocketBaseClient;
import com.huydt.socket_base.client.protocol.InboundMsg;
import org.json.JSONObject;

/**
 * Loto-specific client.
 *
 * Routes CUSTOM_MSG by tag to handler methods.
 * Automatically wires {@link LotoRoomInfo} as the room factory.
 */
public class LotoClient extends SocketBaseClient {

    public LotoClient(Builder b) {
        super(b);
        getSession().setRoomFactory(LotoRoomInfo::new);
    }

    // ---------------------------------------------------------- custom routing

    @Override
    protected void dispatchCustom(InboundMsg msg, JSONObject payload) {
        String tag      = payload.optString("tag", "");
        JSONObject data = payload.optJSONObject("data");

        switch (tag) {
            case "ROUND_START":          onRoundStart(data);         break;
            case "NUMBER_DRAWN":         onNumberDrawn(data);        break;
            case "ROUND_END":            onRoundEnd(data);           break;
            case "ROUND_RESET":          onRoundReset(data);         break;
            case "GAME_OVER":            onGameOver(data);           break;
            case "JACKPOT_UPDATE":       onJackpotUpdate(data);      break;
            case "AUTO_START_SCHEDULED": onAutoStartScheduled(data); break;
            default:
                super.dispatchCustom(msg, payload);
        }
    }

    // ---------------------------------------------------- overridable handlers

    protected void onRoundStart(JSONObject data)         {}
    protected void onNumberDrawn(JSONObject data)        {}
    protected void onRoundEnd(JSONObject data)           {}
    protected void onRoundReset(JSONObject data)         {}
    protected void onGameOver(JSONObject data)           {}
    protected void onJackpotUpdate(JSONObject data)      {}
    protected void onAutoStartScheduled(JSONObject data) {}

    // ---------------------------------------------------------- game actions

    /** Buy one page in the current room. */
    public boolean buyPage() {
        return custom("BUY_PAGE", null);
    }

    /** Manually start the game (must be in room). */
    public boolean startGame() {
        return custom("START_GAME", null);
    }

    /** Claim bingo / win. */
    public boolean claimWin() {
        return custom("CLAIM_WIN", null);
    }

    /** Reset room to WAITING (admin only). */
    public boolean resetRoom() {
        return custom("RESET_ROOM", null);
    }

    // --------------------------------------------------------- self info helpers

    public LotoPlayerInfo getSelfInfo() {
        if (getSession().getCurrentRoom() == null) return null;
        return (LotoPlayerInfo) getSession().getCurrentRoom()
                .getPlayer(getSession().getPlayerId());
    }

    public LotoRoomInfo getCurrentLotoRoom() {
        return (LotoRoomInfo) getSession().getCurrentRoom();
    }
}

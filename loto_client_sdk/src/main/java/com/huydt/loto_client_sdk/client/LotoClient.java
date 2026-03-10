package com.huydt.loto_client_sdk.client;

import com.huydt.loto_client_sdk.model.LotoRoomInfo;
import com.huydt.socket_base.client.core.SocketBaseClient;
import com.huydt.socket_base.client.protocol.InboundMsg;
import org.json.JSONObject;

/**
 * Loto-specific client.
 *
 * Routes incoming CUSTOM_MSG by tag to dedicated handler methods.
 * Automatically wires {@link LotoRoomInfo} as the room factory.
 *
 * <pre>{@code
 * LotoClient client = new LotoClient(
 *     new SocketBaseClient.Builder()
 *         .host("localhost")
 *         .port(9001)
 *         .name("Alice")
 * );
 * client.connect();
 * }</pre>
 */
public class LotoClient extends SocketBaseClient {

    public LotoClient(Builder b) {
        super(b);
        // Wire LotoRoomInfo factory so WELCOME/ROOM_SNAPSHOT use the right subclass
        getSession().setRoomFactory(LotoRoomInfo::new);
    }

    // ---------------------------------------------------------- custom routing

    @Override
    protected void dispatchCustom(InboundMsg msg, JSONObject payload) {
        String tag      = payload.optString("tag", "");
        JSONObject data = payload.optJSONObject("data");

        switch (tag) {
            case "ROUND_START":    onRoundStart(data);    break;
            case "NUMBER_DRAWN":   onNumberDrawn(data);   break;
            case "ROUND_END":      onRoundEnd(data);      break;
            case "GAME_OVER":      onGameOver(data);      break;
            case "JACKPOT_UPDATE": onJackpotUpdate(data); break;
            default:
                // Unknown tags fall through to generic CUSTOM_MSG event on the bus
                super.dispatchCustom(msg, payload);
        }
    }

    // ---------------------------------------------------------- game handlers
    // Override these in your app to react to server events.

    /** Server started a new round. data: {@code round} (int) */
    protected void onRoundStart(JSONObject data) {}

    /** Server drew a number. data: {@code number} (int), {@code drawnNumbers} (array) */
    protected void onNumberDrawn(JSONObject data) {}

    /** A round ended. data: {@code round} (int) */
    protected void onRoundEnd(JSONObject data) {}

    /** Game over — someone won. data: {@code winnerId} (string), {@code jackpot} (long) */
    protected void onGameOver(JSONObject data) {}

    /** Jackpot amount changed. data: {@code jackpot} (long) */
    protected void onJackpotUpdate(JSONObject data) {}

    // ---------------------------------------------------------- game actions

    /** Buy one page/ticket in the current room. */
    public boolean buyPage() {
        return custom("BUY_PAGE", null);
    }

    /** Signal ready to start. */
    public boolean ready() {
        return custom("READY", null);
    }
}

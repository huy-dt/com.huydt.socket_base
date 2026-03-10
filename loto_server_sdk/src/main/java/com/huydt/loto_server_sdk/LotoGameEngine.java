package com.huydt.loto_server_sdk;

import com.huydt.loto_server_sdk.model.LotoPlayer;
import com.huydt.loto_server_sdk.model.LotoRoom;
import com.huydt.socket_base.server.admin.AdminService;
import com.huydt.socket_base.server.core.RoomManager;
import com.huydt.socket_base.server.model.Room;
import com.huydt.socket_base.server.model.Player;
import com.huydt.socket_base.server.protocol.OutboundMsg;
import org.json.JSONArray;
import org.json.JSONObject;

import java.util.*;
import java.util.concurrent.*;

/**
 * Manages game state for a single LotoRoom.
 *
 * Lifecycle:
 *   startRound()  → draws numbers one by one via scheduler
 *   checkBingo()  → called when a player claims win
 *   endRound()    → cleans up, schedules next round via timeAutoReset
 */
public class LotoGameEngine {

    private static final int DRAW_INTERVAL_MS = 3_000;  // 3s between each number draw
    private static final int MAX_NUMBER       = 90;

    private final LotoRoom       room;
    private final RoomManager    roomManager;
    private final AdminService   admin;

    private final List<Integer>      bag          = new ArrayList<>();
    private final ScheduledExecutorService scheduler =
            Executors.newSingleThreadScheduledExecutor();

    private ScheduledFuture<?> drawFuture;
    private ScheduledFuture<?> autoStartFuture;
    private ScheduledFuture<?> autoResetFuture;

    private int round = 0;

    public LotoGameEngine(LotoRoom room, RoomManager roomManager, AdminService admin) {
        this.room        = room;
        this.roomManager = roomManager;
        this.admin       = admin;
    }

    // ---------------------------------------------------------------- start

    /**
     * Begin a round: shuffle bag, set state PLAYING, broadcast ROUND_START.
     */
    public synchronized void startRound() {
        cancelAutoStart();

        round++;
        room.drawnNumbers.clear();
        room.round = round;

        // Fill bag 1-90, shuffle
        bag.clear();
        for (int i = 1; i <= MAX_NUMBER; i++) bag.add(i);
        Collections.shuffle(bag);

        // Clear all player pages
        for (Player p : room.getPlayers()) {
            ((LotoPlayer) p).clearPages();
        }

        admin.changeRoomState(room.getId(), Room.RoomState.PLAYING);

        JSONObject payload = new JSONObject()
                .put("round", round)
                .put("roomId", room.getId());
        admin.broadcast(room.getId(), "ROUND_START", payload);

        System.out.println("[Game:" + room.getId() + "] Round " + round + " started");

        // Schedule number draws
        drawFuture = scheduler.scheduleAtFixedRate(
                this::drawNext, DRAW_INTERVAL_MS, DRAW_INTERVAL_MS, TimeUnit.MILLISECONDS);
    }

    // --------------------------------------------------------------- draw

    private synchronized void drawNext() {
        if (bag.isEmpty()) {
            // No winner — end round
            endRound(null);
            return;
        }

        int number = bag.remove(0);
        room.drawnNumbers.add(number);

        JSONObject payload = new JSONObject()
                .put("number", number)
                .put("drawnNumbers", new JSONArray(room.drawnNumbers))
                .put("remaining", bag.size());
        admin.broadcast(room.getId(), "NUMBER_DRAWN", payload);

        System.out.println("[Game:" + room.getId() + "] Drew: " + number
                + " (" + bag.size() + " left)");
    }

    // --------------------------------------------------------------- bingo check

    /**
     * Called when a player sends CLAIM_WIN.
     * Returns true and ends round if the claim is valid.
     */
    public synchronized boolean checkBingo(LotoPlayer player) {
        if (room.getState() != Room.RoomState.PLAYING) return false;

        // Validation: player must have at least 1 page
        // TODO: validate page numbers against drawnNumbers when LotoPage has grid
        boolean valid = player.getPageCount() > 0;

        if (valid) {
            endRound(player);
        } else {
            // False claim — notify only the claimer
            JSONObject penalty = new JSONObject()
                    .put("code", "INVALID_CLAIM")
                    .put("detail", "Your pages do not match drawn numbers");
            admin.sendTo(player.getId(), "ERROR", penalty);
        }
        return valid;
    }

    // --------------------------------------------------------------- end

    private synchronized void endRound(LotoPlayer winner) {
        cancelDraw();

        long jackpot = room.jackpot;

        if (winner != null) {
            winner.money += jackpot;
            room.jackpot = 0;

            JSONObject winPayload = new JSONObject()
                    .put("winnerId",   winner.getId())
                    .put("winnerName", winner.getName())
                    .put("jackpot",    jackpot)
                    .put("round",      round);
            admin.broadcast(room.getId(), "GAME_OVER", winPayload);
            admin.pushPlayerUpdate(winner);

            System.out.println("[Game:" + room.getId() + "] Winner: "
                    + winner.getName() + " jackpot=" + jackpot);
        } else {
            JSONObject noWin = new JSONObject()
                    .put("round",   round)
                    .put("message", "No winner — all numbers drawn");
            admin.broadcast(room.getId(), "GAME_OVER", noWin);
            System.out.println("[Game:" + room.getId() + "] No winner");
        }

        admin.changeRoomState(room.getId(), Room.RoomState.ENDED);

        // Schedule auto-reset if configured
        if (room.timeAutoReset > 0) {
            autoResetFuture = scheduler.schedule(() -> {
                reset();
            }, room.timeAutoReset, TimeUnit.MILLISECONDS);
            System.out.println("[Game:" + room.getId() + "] Auto-reset in "
                    + room.timeAutoReset + "ms");
        }
    }

    // --------------------------------------------------------------- reset

    public synchronized void reset() {
        cancelDraw();
        cancelAutoReset();

        room.drawnNumbers.clear();
        room.round  = round;
        room.jackpot = 0;

        admin.changeRoomState(room.getId(), Room.RoomState.WAITING);

        JSONObject payload = new JSONObject().put("roomId", room.getId());
        admin.broadcast(room.getId(), "ROUND_RESET", payload);

        System.out.println("[Game:" + room.getId() + "] Room reset to WAITING");
    }

    // --------------------------------------------------------------- auto-start

    /**
     * Schedule auto-start when enough players are ready.
     * Call this after a player buys a page or joins.
     */
    public synchronized void scheduleAutoStart() {
        if (room.timeAutoStart <= 0) return;
        if (room.getState() != Room.RoomState.WAITING) return;
        if (autoStartFuture != null && !autoStartFuture.isDone()) return;

        System.out.println("[Game:" + room.getId() + "] Auto-start in "
                + room.timeAutoStart + "ms");

        JSONObject payload = new JSONObject()
                .put("startsInMs", room.timeAutoStart);
        admin.broadcast(room.getId(), "AUTO_START_SCHEDULED", payload);

        autoStartFuture = scheduler.schedule(
                this::startRound, room.timeAutoStart, TimeUnit.MILLISECONDS);
    }

    public synchronized void cancelAutoStart() {
        if (autoStartFuture != null) { autoStartFuture.cancel(false); autoStartFuture = null; }
    }

    // --------------------------------------------------------------- helpers

    private void cancelDraw() {
        if (drawFuture != null) { drawFuture.cancel(false); drawFuture = null; }
    }

    private void cancelAutoReset() {
        if (autoResetFuture != null) { autoResetFuture.cancel(false); autoResetFuture = null; }
    }

    public void shutdown() {
        scheduler.shutdownNow();
    }

    public int getRound() { return round; }
}

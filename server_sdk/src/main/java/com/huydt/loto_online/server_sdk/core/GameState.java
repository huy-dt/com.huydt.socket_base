package com.huydt.loto_online.server_sdk.core;

/**
 * Maps to socket_base {@link com.huydt.socket_base.server.model.Room.RoomState}:
 * <pre>
 *   WAITING  ↔ RoomState.WAITING
 *   VOTING   ↔ RoomState.STARTING
 *   PLAYING  ↔ RoomState.PLAYING
 *   PAUSED   ↔ RoomState.PAUSED
 *   ENDED    ↔ RoomState.ENDED
 * </pre>
 */
public enum GameState {
    WAITING, VOTING, PLAYING, PAUSED, ENDED;

    public com.huydt.socket_base.server.model.Room.RoomState toRoomState() {
        switch (this) {
            case WAITING:  return com.huydt.socket_base.server.model.Room.RoomState.WAITING;
            case VOTING:   return com.huydt.socket_base.server.model.Room.RoomState.STARTING;
            case PLAYING:  return com.huydt.socket_base.server.model.Room.RoomState.PLAYING;
            case PAUSED:   return com.huydt.socket_base.server.model.Room.RoomState.PAUSED;
            case ENDED:    return com.huydt.socket_base.server.model.Room.RoomState.ENDED;
            default:       return com.huydt.socket_base.server.model.Room.RoomState.WAITING;
        }
    }

    public static GameState fromRoomState(com.huydt.socket_base.server.model.Room.RoomState rs) {
        switch (rs) {
            case WAITING:  return WAITING;
            case STARTING: return VOTING;
            case PLAYING:  return PLAYING;
            case PAUSED:   return PAUSED;
            case ENDED:
            case CLOSED:   return ENDED;
            default:       return WAITING;
        }
    }
}

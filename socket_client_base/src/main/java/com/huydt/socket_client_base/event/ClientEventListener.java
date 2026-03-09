package com.huydt.socket_client_base.event;

/**
 * Functional interface for handling {@link ClientEvent}s.
 *
 * <pre>
 * client.on(ClientEventType.WELCOME, e -> System.out.println("Joined as " + e.getPlayerId()));
 * </pre>
 */
@FunctionalInterface
public interface ClientEventListener {
    void onEvent(ClientEvent event);
}

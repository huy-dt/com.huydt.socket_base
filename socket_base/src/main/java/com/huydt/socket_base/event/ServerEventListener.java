package com.huydt.socket_base.event;

/**
 * Functional interface for listening to {@link ServerEvent}s.
 *
 * <pre>
 * bus.on(EventType.PLAYER_JOINED, event -> System.out.println(event));
 * </pre>
 */
@FunctionalInterface
public interface ServerEventListener {
    void onEvent(ServerEvent event);
}

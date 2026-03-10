package com.huydt.socket_base.client.event;

import java.util.List;
import java.util.Map;
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.CopyOnWriteArrayList;
import java.util.function.BiConsumer;

/**
 * Simple synchronous event bus. Thread-safe.
 */
public final class ClientEventBus {

    private final Map<ClientEventType, List<ClientEventListener>> listeners =
            new ConcurrentHashMap<>();

    private BiConsumer<ClientEvent, Throwable> errorHandler = null;

    /** Register a listener for the given event type. Returns {@code this} for chaining. */
    public ClientEventBus on(ClientEventType type, ClientEventListener listener) {
        listeners.computeIfAbsent(type, k -> new CopyOnWriteArrayList<>()).add(listener);
        return this;
    }

    /** Remove all listeners for a given type. */
    public void off(ClientEventType type) {
        listeners.remove(type);
    }

    /** Called if a listener throws. */
    public ClientEventBus onError(BiConsumer<ClientEvent, Throwable> handler) {
        this.errorHandler = handler;
        return this;
    }

    /** Emit an event to all registered listeners. */
    public void emit(ClientEvent event) {
        List<ClientEventListener> list = listeners.get(event.getType());
        if (list == null) return;
        for (ClientEventListener l : list) {
            try {
                l.onEvent(event);
            } catch (Throwable t) {
                if (errorHandler != null) {
                    try { errorHandler.accept(event, t); } catch (Throwable ignored) {}
                } else {
                    System.err.println("[ClientEventBus] Listener threw for "
                            + event.getType() + ": " + t.getMessage());
                }
            }
        }
    }
}

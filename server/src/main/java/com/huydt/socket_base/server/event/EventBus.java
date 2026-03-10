package com.huydt.socket_base.server.event;

import java.util.*;
import java.util.concurrent.CopyOnWriteArrayList;

/**
 * Lightweight synchronous event bus.
 *
 * <h3>Usage</h3>
 * <pre>
 * EventBus bus = new EventBus();
 *
 * // Subscribe to a specific type
 * bus.on(EventType.PLAYER_JOINED, e -> log(e.getPlayer().getName()));
 *
 * // Subscribe to all types
 * bus.onAny(e -> metrics.record(e));
 *
 * // Handle listener errors (optional — defaults to stderr print)
 * bus.onError((event, ex) -> myLogger.error("Listener failed for " + event.getType(), ex));
 *
 * // Unsubscribe a specific listener
 * ServerEventListener handler = e -> doSomething(e);
 * bus.on(EventType.ROOM_CREATED, handler);
 * bus.off(EventType.ROOM_CREATED, handler);
 *
 * // Remove all listeners for a type
 * bus.off(EventType.ROOM_CREATED);
 * </pre>
 */
public class EventBus {

    /** Called when a listener throws. Receives the event that triggered it and the exception. */
    @FunctionalInterface
    public interface ErrorHandler {
        void onError(ServerEvent event, Throwable ex);
    }

    private final Map<EventType, List<ServerEventListener>> listeners = new EnumMap<>(EventType.class);
    private final List<ServerEventListener> globalListeners            = new CopyOnWriteArrayList<>();
    private volatile ErrorHandler errorHandler                         = null;

    // ── Subscribe ─────────────────────────────────────────────────────

    /**
     * Registers a listener for a specific event type.
     * Returns {@code this} for chaining.
     */
    public synchronized EventBus on(EventType type, ServerEventListener listener) {
        if (listener == null) throw new IllegalArgumentException("listener must not be null");
        listeners.computeIfAbsent(type, k -> new CopyOnWriteArrayList<>()).add(listener);
        return this;
    }

    /**
     * Registers a listener that receives ALL event types.
     * Returns {@code this} for chaining.
     */
    public EventBus onAny(ServerEventListener listener) {
        if (listener == null) throw new IllegalArgumentException("listener must not be null");
        globalListeners.add(listener);
        return this;
    }

    /**
     * Sets a custom error handler invoked when any listener throws.
     * If not set, the exception is printed to stderr with a stack trace.
     */
    public EventBus onError(ErrorHandler handler) {
        this.errorHandler = handler;
        return this;
    }

    // ── Unsubscribe ───────────────────────────────────────────────────

    /** Removes a specific listener for the given event type. */
    public synchronized void off(EventType type, ServerEventListener listener) {
        List<ServerEventListener> list = listeners.get(type);
        if (list != null) list.remove(listener);
    }

    /** Removes all listeners for the given event type. */
    public synchronized void off(EventType type) {
        listeners.remove(type);
    }

    /** Removes a listener registered via {@link #onAny}. */
    public void offAny(ServerEventListener listener) {
        globalListeners.remove(listener);
    }

    /** Removes all listeners (type-specific and global). Resets the error handler. */
    public synchronized void clear() {
        listeners.clear();
        globalListeners.clear();
    }

    // ── Publish ───────────────────────────────────────────────────────

    /**
     * Emits an event synchronously to all matching listeners, then to global listeners.
     *
     * <p>Listener execution order:
     * <ol>
     *   <li>Type-specific listeners (in registration order)</li>
     *   <li>Global ({@code onAny}) listeners (in registration order)</li>
     * </ol>
     *
     * <p>If a listener throws, execution continues for remaining listeners.
     * The exception is forwarded to {@link #onError} if set, otherwise
     * printed to stderr with a full stack trace.
     */
    public void emit(ServerEvent event) {
        if (event == null) return;

        // type-specific
        List<ServerEventListener> typed = listeners.get(event.getType());
        if (typed != null) {
            for (ServerEventListener l : typed) safeCall(l, event);
        }

        // global
        for (ServerEventListener l : globalListeners) safeCall(l, event);
    }

    // ── Convenience overloads ─────────────────────────────────────────

    public void emit(EventType type) {
        emit(ServerEvent.of(type));
    }

    public void emit(EventType type, String message) {
        emit(ServerEvent.of(type, message));
    }

    // ── Util ──────────────────────────────────────────────────────────

    /** Returns the number of type-specific listeners registered for {@code type}. */
    public int listenerCount(EventType type) {
        List<ServerEventListener> list = listeners.get(type);
        return list == null ? 0 : list.size();
    }

    public int globalListenerCount() { return globalListeners.size(); }

    private void safeCall(ServerEventListener l, ServerEvent event) {
        try {
            l.onEvent(event);
        } catch (Throwable ex) {
            ErrorHandler h = this.errorHandler;
            if (h != null) {
                try { h.onError(event, ex); } catch (Throwable ignored) {}
            } else {
                System.err.println("[EventBus] Listener threw for " + event.getType()
                        + ": " + ex.getMessage());
                ex.printStackTrace(System.err);
            }
        }
    }
}

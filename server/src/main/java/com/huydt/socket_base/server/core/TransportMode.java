package com.huydt.socket_base.server.core;

/**
 * Selects which network transports the server opens.
 *
 * <ul>
 *   <li>{@code TCP}  — raw TCP socket only  (--tcp)</li>
 *   <li>{@code WS}   — WebSocket only       (--ws)</li>
 *   <li>{@code BOTH} — TCP + WebSocket      (--both)</li>
 * </ul>
 */
public enum TransportMode {
    TCP, WS, BOTH;

    public boolean usesTcp() { return this == TCP || this == BOTH; }
    public boolean usesWs()  { return this == WS  || this == BOTH; }

    /** Parse from CLI args: "--tcp", "--ws", "--both". */
    public static TransportMode fromArg(String arg) {
        if (arg == null) return BOTH;
        switch (arg.toLowerCase()) {
            case "--tcp":  return TCP;
            case "--ws":   return WS;
            case "--both": return BOTH;
            default:       return BOTH;
        }
    }
}

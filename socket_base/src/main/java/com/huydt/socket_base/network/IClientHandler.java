package com.huydt.socket_base.network;

/**
 * Abstraction over a single client connection.
 * Implemented by both {@link TcpClientHandler} and {@link WsClientHandler}.
 */
public interface IClientHandler {

    /** The temporary connection id assigned when the socket connected. */
    String getConnectionId();

    /** The remote IP address string (e.g. "192.168.1.5"). */
    String getRemoteIp();

    /** Whether the underlying socket / session is still open. */
    boolean isConnected();

    /** Sends a JSON string to this client (newline-delimited for TCP). */
    void send(String json);

    /** Closes the underlying connection. */
    void close();
}

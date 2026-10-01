package org.nomad.client;

/**
 * The smallest possible text-frame socket, so that the JDK WebSocket (simulator) and OkHttp (Android) can implement it
 * in a few lines. No reactive types, no platform types. Every connect() opens a NEW connection with its own listener.
 */
public interface Transport {
    /** Opens a connection asynchronously and returns at once; the result arrives through the listener. */
    void connect(String url, TransportListener listener);

    /** Queues one text frame. Frames of one connection keep their order. @return false if there is no open connection. */
    boolean send(String text);

    /** Closes the current connection, if any. */
    void close();
}

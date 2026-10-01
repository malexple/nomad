package org.nomad.client;

/** Callbacks of a connection. They may arrive on any thread; the engine moves them to its own thread. */
public interface TransportListener {
    /** The socket is open (the node has not yet sent its challenge). */
    void onConnected();

    void onText(String text);

    /** The connection is gone: closed by either side, failed to open, or broken. Called at most once per connect(). */
    void onClosed(String reason);
}

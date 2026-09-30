package org.nomad.client;

import java.io.IOException;

/** Where the sealed client state lives: a file on the JVM, a private file or database on Android. */
public interface StateStore {
    /** @return the stored bytes, or null if nothing has been stored yet */
    byte[] load() throws IOException;

    void save(byte[] data) throws IOException;
}

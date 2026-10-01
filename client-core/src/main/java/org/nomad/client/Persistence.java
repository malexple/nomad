package org.nomad.client;

import java.io.IOException;

/** Writes the whole client state to stable storage. The engine calls it before a ciphertext leaves the device. */
public interface Persistence {
    Persistence NONE = () -> { };

    void save() throws IOException;
}

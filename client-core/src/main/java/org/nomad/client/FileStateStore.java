package org.nomad.client;

import java.io.IOException;
import java.nio.file.AtomicMoveNotSupportedException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.StandardCopyOption;

/** One file, replaced atomically (written next to it first), so a crash never leaves half a state. */
public final class FileStateStore implements StateStore {
    private final Path file;

    public FileStateStore(Path file) {
        this.file = file;
    }

    @Override
    public byte[] load() throws IOException {
        return Files.exists(file) ? Files.readAllBytes(file) : null;
    }

    @Override
    public void save(byte[] data) throws IOException {
        Path tmp = file.resolveSibling(file.getFileName() + ".tmp");
        Files.write(tmp, data);
        try {
            Files.move(tmp, file, StandardCopyOption.ATOMIC_MOVE, StandardCopyOption.REPLACE_EXISTING);
        } catch (AtomicMoveNotSupportedException e) {
            Files.move(tmp, file, StandardCopyOption.REPLACE_EXISTING);
        }
    }
}

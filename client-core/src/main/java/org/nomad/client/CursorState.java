package org.nomad.client;

import java.util.Iterator;
import java.util.LinkedHashSet;
import java.util.UUID;

/**
 * Read position (epoch, seq) plus a bounded set of already seen envelope ids.
 * If the epoch changes after a node failover, the cursor is reset to 0 and duplicates are dropped by id.
 */
public final class CursorState {
    private static final int MAX_SEEN = 10_000;

    private long epoch;
    private long seq;
    private final LinkedHashSet<UUID> seen = new LinkedHashSet<>();

    public CursorState() {}

    public CursorState(long epoch, long seq) {
        this.epoch = epoch;
        this.seq = seq;
    }

    public long epoch() {
        return epoch;
    }

    public long seq() {
        return seq;
    }

    void setEpoch(long epoch) {
        this.epoch = epoch;
    }

    void setSeq(long seq) {
        this.seq = seq;
    }

    /** @return true if the id is new */
    boolean markSeen(UUID id) {
        boolean added = seen.add(id);
        if (seen.size() > MAX_SEEN) {
            Iterator<UUID> it = seen.iterator();
            it.next();
            it.remove();
        }
        return added;
    }
}

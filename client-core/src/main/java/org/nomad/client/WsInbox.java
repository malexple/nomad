package org.nomad.client;

import java.util.ArrayList;
import java.util.List;
import java.util.TreeMap;
import java.util.UUID;
import org.nomad.core.BinReader;
import org.nomad.core.BinWriter;

/**
 * Cursor logic for the WebSocket client: dedup by envelope id, epoch reset after a server failover and
 * a holding area for messages that cannot be decrypted yet (their keys may still be on the way).
 * The caller sends WsProtocol.sync(afterSeq(), ...) after auth, after every "wake" and while syncAgain is true,
 * and acknowledges only up to safeAckSeq(): the server keeps held messages until they are resolved.
 * The whole state (cursor, seen ids, held messages) can be persisted with writeTo()/readFrom().
 */
public final class WsInbox {
    private static final int MAX_HELD = 1000;

    public record Result(List<Received> fresh, boolean syncAgain) {}

    private final CursorState cursor;
    private final TreeMap<Long, Received> held = new TreeMap<>();

    public WsInbox(CursorState cursor) {
        this.cursor = cursor;
    }

    public synchronized long afterSeq() {
        return cursor.seq();
    }

    public synchronized Result onMsgs(WsProtocol.Msgs m) {
        if (cursor.epoch() != 0 && m.epoch() != cursor.epoch()) {
            cursor.setEpoch(m.epoch());
            cursor.setSeq(0);
            return new Result(List.of(), true);
        }
        cursor.setEpoch(m.epoch());
        List<Received> fresh = new ArrayList<>();
        for (Received r : m.items()) {
            cursor.setSeq(Math.max(cursor.seq(), r.seq()));
            if (cursor.markSeen(r.envelopeId())) {
                fresh.add(r);
            }
        }
        return new Result(fresh, m.more());
    }

    public synchronized void hold(Received r) {
        held.put(r.seq(), r);
        while (held.size() > MAX_HELD) {
            held.pollFirstEntry();
        }
    }

    public synchronized void release(Received r) {
        held.remove(r.seq());
    }

    public synchronized List<Received> heldItems() {
        return new ArrayList<>(held.values());
    }

    public synchronized int heldCount() {
        return held.size();
    }

    /** Highest seq that may be acknowledged without dropping a held message. */
    public synchronized long safeAckSeq() {
        long cur = cursor.seq();
        return held.isEmpty() ? cur : Math.min(cur, held.firstKey() - 1);
    }

    // ---------------------------------------------------------------- persistence

    public synchronized void writeTo(BinWriter w) {
        cursor.writeTo(w);
        w.i32(held.size());
        for (Received r : held.values()) {
            w.i64(r.seq())
                    .i64(r.envelopeId().getMostSignificantBits())
                    .i64(r.envelopeId().getLeastSignificantBits())
                    .i32(r.version())
                    .bytes(r.payload());
        }
    }

    public static WsInbox readFrom(BinReader r) {
        WsInbox inbox = new WsInbox(CursorState.readFrom(r));
        int n = r.count();
        for (int i = 0; i < n; i++) {
            long seq = r.i64();
            long msb = r.i64();
            UUID id = new UUID(msb, r.i64());
            int version = r.i32();
            inbox.held.put(seq, new Received(seq, id, version, r.bytes()));
        }
        return inbox;
    }
}

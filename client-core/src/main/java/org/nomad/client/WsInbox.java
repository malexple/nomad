package org.nomad.client;

import java.util.ArrayList;
import java.util.List;

/**
 * Cursor logic for the WebSocket client: dedup by envelope id and epoch reset after a server failover.
 * The caller sends WsProtocol.sync(afterSeq(), ...) after auth, after every "wake", and while syncAgain is true.
 */
public final class WsInbox {
    public record Result(List<Received> fresh, boolean syncAgain) {}

    private final CursorState cursor;

    public WsInbox(CursorState cursor) {
        this.cursor = cursor;
    }

    public long afterSeq() {
        return cursor.seq();
    }

    public Result onMsgs(WsProtocol.Msgs m) {
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
}

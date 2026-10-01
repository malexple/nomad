package org.nomad.client;

import java.util.LinkedHashMap;
import java.util.Map;
import org.nomad.core.BinReader;
import org.nomad.core.BinWriter;

/**
 * What the engine itself must remember across restarts: whether the prekeys were uploaded, and the outbox: the
 * encrypted envelopes (as ready-to-send frames) that the node has not acknowledged yet. They are resent after a
 * reconnect or a restart; the node deduplicates them by envelope id.
 */
public final class EngineState {
    private boolean prekeysPublished;
    private final LinkedHashMap<String, String> outbox = new LinkedHashMap<>();

    public synchronized boolean prekeysPublished() {
        return prekeysPublished;
    }

    public synchronized void setPrekeysPublished(boolean v) {
        this.prekeysPublished = v;
    }

    /** envelope id to the send frame; mutate it only on the engine thread */
    public Map<String, String> outbox() {
        return outbox;
    }

    synchronized void writeTo(BinWriter w) {
        w.bool(prekeysPublished).i32(outbox.size());
        for (Map.Entry<String, String> e : outbox.entrySet()) {
            w.str(e.getKey()).str(e.getValue());
        }
    }

    static EngineState readFrom(BinReader r) {
        EngineState s = new EngineState();
        s.prekeysPublished = r.bool();
        int n = r.count();
        for (int i = 0; i < n; i++) {
            String id = r.str();
            s.outbox.put(id, r.str());
        }
        return s;
    }
}

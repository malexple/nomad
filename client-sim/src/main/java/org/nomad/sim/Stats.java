package org.nomad.sim;

import java.util.ArrayList;
import java.util.Collections;
import java.util.List;
import java.util.Set;
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.atomic.AtomicInteger;

/** Payload format: sim|from|to|index|sentAtMillis. */
final class Stats {
    final Set<String> seen = ConcurrentHashMap.newKeySet();
    final AtomicInteger delivered = new AtomicInteger();
    final AtomicInteger duplicates = new AtomicInteger();
    final AtomicInteger foreign = new AtomicInteger();
    final AtomicInteger sendAcks = new AtomicInteger();
    final AtomicInteger errors = new AtomicInteger();
    final AtomicInteger undecryptable = new AtomicInteger();
    final List<Long> latencies = Collections.synchronizedList(new ArrayList<>());

    void onReceive(String text) {
        String[] p = text.split("\\|");
        if (p.length != 5 || !p[0].equals("sim")) {
            foreign.incrementAndGet();
            return;
        }
        String key = p[1] + "->" + p[2] + "#" + p[3];
        if (!seen.add(key)) {
            duplicates.incrementAndGet();
            return;
        }
        delivered.incrementAndGet();
        latencies.add(System.currentTimeMillis() - Long.parseLong(p[4]));
    }

    long percentile(double q) {
        List<Long> copy;
        synchronized (latencies) {
            copy = new ArrayList<>(latencies);
        }
        if (copy.isEmpty()) {
            return -1;
        }
        Collections.sort(copy);
        int idx = (int) Math.min(copy.size() - 1, Math.floor(q * copy.size()));
        return copy.get(idx);
    }
}

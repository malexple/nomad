package org.nomad.mailbox;

import java.util.HashMap;
import java.util.List;
import java.util.Map;
import java.util.Optional;
import java.util.TreeMap;
import org.nomad.core.OneTimePrekey;
import org.nomad.core.PrekeyBundle;

public final class InMemoryPrekeyDirectory implements PrekeyDirectory {
    private final Map<String, PrekeyBundle> identities = new HashMap<>();
    private final Map<String, TreeMap<Integer, byte[]>> pools = new HashMap<>();

    @Override
    public synchronized void publish(PrekeyBundle b, List<OneTimePrekey> newOpks) {
        String uid = b.uid();
        identities.put(uid, b.withOpk(null));
        TreeMap<Integer, byte[]> pool = pools.computeIfAbsent(uid, k -> new TreeMap<>());
        for (OneTimePrekey k : newOpks) {
            if (pool.size() >= MAX_ONE_TIME_PREKEYS) {
                break;
            }
            pool.putIfAbsent(k.id(), k.pub());
        }
    }

    @Override
    public synchronized Optional<PrekeyBundle> fetch(String uid) {
        PrekeyBundle base = identities.get(uid);
        if (base == null) {
            return Optional.empty();
        }
        TreeMap<Integer, byte[]> pool = pools.get(uid);
        if (pool == null || pool.isEmpty()) {
            return Optional.of(base);
        }
        Map.Entry<Integer, byte[]> first = pool.pollFirstEntry();
        return Optional.of(base.withOpk(new OneTimePrekey(first.getKey(), first.getValue())));
    }

    @Override
    public synchronized int oneTimePrekeyCount(String uid) {
        TreeMap<Integer, byte[]> pool = pools.get(uid);
        return pool == null ? 0 : pool.size();
    }
}

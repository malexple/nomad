package org.nomad.bus;

import java.util.List;
import java.util.concurrent.CopyOnWriteArrayList;
import java.util.function.Consumer;

/** In-process bus for a single node or tests. Redis Pub/Sub will implement the same interface. */
public final class LocalWakeBus implements WakeBus {
    private final List<Consumer<String>> listeners = new CopyOnWriteArrayList<>();

    @Override
    public void publish(String mailboxId) {
        for (Consumer<String> l : listeners) {
            l.accept(mailboxId);
        }
    }

    @Override
    public Runnable subscribe(Consumer<String> listener) {
        listeners.add(listener);
        return () -> listeners.remove(listener);
    }
}

package org.nomad.mailbox;

import static org.junit.jupiter.api.Assertions.*;

import java.util.UUID;
import org.junit.jupiter.api.Test;
import org.nomad.core.Envelope;

class InMemoryMailboxStoreTest {
    private static final String MBX = "a".repeat(64);
    private static final String OTHER = "b".repeat(64);

    private static Envelope env(String mailbox, UUID id, long expiryDay) {
        return new Envelope(0, id, mailbox, new byte[] {1, 2, 3}, expiryDay);
    }

    @Test
    void appendIsIdempotentById() {
        var store = new InMemoryMailboxStore();
        UUID id = UUID.randomUUID();
        AppendResult first = store.append(env(MBX, id, 100));
        AppendResult second = store.append(env(MBX, id, 100));
        assertFalse(first.duplicate());
        assertTrue(second.duplicate());
        assertEquals(first.seq(), second.seq());
    }

    @Test
    void readReturnsOnlyOwnMailboxAfterCursorInOrder() {
        var store = new InMemoryMailboxStore();
        long s1 = store.append(env(MBX, UUID.randomUUID(), 100)).seq();
        store.append(env(OTHER, UUID.randomUUID(), 100));
        long s3 = store.append(env(MBX, UUID.randomUUID(), 100)).seq();

        ReadResult all = store.read(MBX, 0, 10);
        assertEquals(2, all.items().size());
        assertEquals(s1, all.items().get(0).seq());

        ReadResult tail = store.read(MBX, s1, 10);
        assertEquals(1, tail.items().size());
        assertEquals(s3, tail.items().get(0).seq());
    }

    @Test
    void ackAndExpiryDeleteEnvelopes() {
        var store = new InMemoryMailboxStore();
        long s1 = store.append(env(MBX, UUID.randomUUID(), 10)).seq();
        long s2 = store.append(env(MBX, UUID.randomUUID(), 500)).seq();
        assertEquals(1, store.purgeExpired(11));
        assertEquals(1, store.read(MBX, 0, 10).items().size());
        assertEquals(1, store.deleteUpTo(MBX, s2));
        assertTrue(store.read(MBX, 0, 10).items().isEmpty());
        assertTrue(s2 > s1);
    }

    @Test
    void epochBumpIsVisibleToReaders() {
        var store = new InMemoryMailboxStore();
        long before = store.read(MBX, 0, 1).epoch();
        store.bumpEpoch();
        assertEquals(before + 1, store.read(MBX, 0, 1).epoch());
    }
}

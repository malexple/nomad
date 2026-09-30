package org.nomad.mailbox;

import static org.junit.jupiter.api.Assertions.*;

import java.util.List;
import java.util.Optional;
import org.junit.jupiter.api.Test;
import org.nomad.core.OneTimePrekey;
import org.nomad.core.PrekeyBundle;

class InMemoryPrekeyDirectoryTest {
    private static byte[] bytes(int n, int v) {
        byte[] b = new byte[n];
        java.util.Arrays.fill(b, (byte) v);
        return b;
    }

    private static PrekeyBundle bundle(int seed) {
        return new PrekeyBundle(bytes(32, seed), bytes(32, 2), bytes(64, 3), 1, bytes(32, 4), bytes(64, 5), null);
    }

    @Test
    void oneTimePrekeysAreHandedOutOnceInOrderThenNone() {
        var dir = new InMemoryPrekeyDirectory();
        PrekeyBundle b = bundle(9);
        dir.publish(b, List.of(new OneTimePrekey(2, bytes(32, 7)), new OneTimePrekey(1, bytes(32, 6))));
        assertEquals(2, dir.oneTimePrekeyCount(b.uid()));

        assertEquals(1, dir.fetch(b.uid()).orElseThrow().opk().id());
        assertEquals(2, dir.fetch(b.uid()).orElseThrow().opk().id());
        Optional<PrekeyBundle> last = dir.fetch(b.uid());
        assertTrue(last.isPresent());
        assertNull(last.get().opk());
    }

    @Test
    void unknownUidHasNoBundle() {
        assertTrue(new InMemoryPrekeyDirectory().fetch("nobody").isEmpty());
    }

    @Test
    void poolIsCapped() {
        var dir = new InMemoryPrekeyDirectory();
        PrekeyBundle b = bundle(11);
        java.util.List<OneTimePrekey> many = new java.util.ArrayList<>();
        for (int i = 1; i <= PrekeyDirectory.MAX_ONE_TIME_PREKEYS + 20; i++) {
            many.add(new OneTimePrekey(i, bytes(32, i % 250)));
        }
        dir.publish(b, many);
        assertEquals(PrekeyDirectory.MAX_ONE_TIME_PREKEYS, dir.oneTimePrekeyCount(b.uid()));
    }
}

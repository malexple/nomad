package org.nomad.client;

import static org.junit.jupiter.api.Assertions.*;

import java.util.List;
import java.util.UUID;
import org.junit.jupiter.api.Test;

class WsInboxTest {
    private static Received rec(long seq, UUID id) {
        return new Received(seq, id, 0, new byte[] {1});
    }

    @Test
    void dropsDuplicatesAndAdvancesCursor() {
        var inbox = new WsInbox(new CursorState());
        UUID id = UUID.randomUUID();
        var r1 = inbox.onMsgs(new WsProtocol.Msgs(1, false, List.of(rec(5, id))));
        assertEquals(1, r1.fresh().size());
        assertEquals(5, inbox.afterSeq());
        var r2 = inbox.onMsgs(new WsProtocol.Msgs(1, false, List.of(rec(5, id))));
        assertTrue(r2.fresh().isEmpty());
    }

    @Test
    void epochChangeResetsCursorAndAsksForResync() {
        var inbox = new WsInbox(new CursorState(1, 42));
        var r = inbox.onMsgs(new WsProtocol.Msgs(2, false, List.of()));
        assertTrue(r.syncAgain());
        assertEquals(0, inbox.afterSeq());
    }

    @Test
    void moreFlagRequestsAnotherSync() {
        var inbox = new WsInbox(new CursorState());
        var r = inbox.onMsgs(new WsProtocol.Msgs(1, true, List.of(rec(1, UUID.randomUUID()))));
        assertTrue(r.syncAgain());
    }

    @Test
    void parsesServerMsgs() {
        String json = "{\"t\":\"msgs\",\"epoch\":3,\"more\":false,\"items\":[{\"seq\":7,\"id\":\""
                + UUID.randomUUID() + "\",\"v\":0,\"ct\":\"AQID\",\"exp\":1}]}";
        var m = WsProtocol.parseMsgs(WsProtocol.parse(json));
        assertEquals(3, m.epoch());
        assertEquals(7, m.items().get(0).seq());
        assertArrayEquals(new byte[] {1, 2, 3}, m.items().get(0).payload());
    }
}

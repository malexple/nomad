package org.nomad.client;

import static org.junit.jupiter.api.Assertions.*;

import java.util.List;
import java.util.UUID;
import org.junit.jupiter.api.Test;

class WsInboxHeldTest {
    @Test
    void ackStopsBelowTheEarliestHeldMessage() {
        var inbox = new WsInbox(new CursorState());
        Received r5 = new Received(5, UUID.randomUUID(), 1, new byte[] {1});
        Received r9 = new Received(9, UUID.randomUUID(), 1, new byte[] {1});
        inbox.onMsgs(new WsProtocol.Msgs(1, false, List.of(r5, r9)));
        assertEquals(9, inbox.safeAckSeq());

        inbox.hold(r5);
        assertEquals(1, inbox.heldCount());
        assertEquals(4, inbox.safeAckSeq());

        inbox.release(r5);
        assertEquals(0, inbox.heldCount());
        assertEquals(9, inbox.safeAckSeq());
    }
}

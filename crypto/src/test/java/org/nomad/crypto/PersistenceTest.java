package org.nomad.crypto;

import static java.nio.charset.StandardCharsets.UTF_8;
import static org.junit.jupiter.api.Assertions.*;

import java.util.List;
import java.util.Optional;
import org.junit.jupiter.api.Test;
import org.nomad.core.BinReader;
import org.nomad.core.BinWriter;
import org.nomad.core.PrekeyBundle;

class PersistenceTest {
    private record Restarted(Identity id, ConversationManager convo) {}

    private static byte[] bytes(String s) {
        return s.getBytes(UTF_8);
    }

    private static String text(Optional<ConversationManager.Decrypted> d) {
        assertTrue(d.isPresent(), "message could not be decrypted");
        return new String(d.get().plaintext(), UTF_8);
    }

    private static Restarted restart(Identity id, ConversationManager convo) {
        BinWriter w = new BinWriter();
        id.writeTo(w);
        convo.writeTo(w);
        BinReader r = new BinReader(w.toByteArray());
        Identity id2 = Identity.readFrom(r);
        return new Restarted(id2, ConversationManager.readFrom(id2, r));
    }

    private static GroupManager restart(Identity id, GroupManager groups) {
        BinWriter w = new BinWriter();
        groups.writeTo(w);
        return GroupManager.readFrom(id, new BinReader(w.toByteArray()));
    }

    @Test
    void pairwiseSessionsAndIdentitySurviveARestartOnBothSides() {
        Identity ia = Identity.generate();
        Identity ib = Identity.generate();
        ConversationManager ca = new ConversationManager(ia);
        ConversationManager cb = new ConversationManager(ib);
        ca.startSession(ib.uid(), ib.publicBundle().withOpk(ib.generateOneTimePrekeys(1).get(0)));
        assertEquals("one", text(cb.decrypt(ca.encryptFor(ib.uid(), bytes("one")))));
        assertEquals("two", text(ca.decrypt(cb.encryptFor(ia.uid(), bytes("two")))));

        byte[] m3 = ca.encryptFor(ib.uid(), bytes("three"));
        byte[] m4 = ca.encryptFor(ib.uid(), bytes("four"));
        byte[] m5 = ca.encryptFor(ib.uid(), bytes("five"));
        assertEquals("five", text(cb.decrypt(m5)));

        Restarted ra = restart(ia, ca);
        Restarted rb = restart(ib, cb);
        assertEquals(ia.uid(), ra.id().uid());
        assertEquals(ib.uid(), rb.id().uid());

        assertEquals("three", text(rb.convo().decrypt(m3)));
        assertEquals("four", text(rb.convo().decrypt(m4)));
        assertEquals("six", text(rb.convo().decrypt(ra.convo().encryptFor(ib.uid(), bytes("six")))));
        assertEquals("seven", text(ra.convo().decrypt(rb.convo().encryptFor(ia.uid(), bytes("seven")))));
    }

    @Test
    void prekeysSurviveARestartBeforeTheFirstMessage() {
        Identity ia = Identity.generate();
        Identity ib = Identity.generate();
        PrekeyBundle bundle = ib.publicBundle().withOpk(ib.generateOneTimePrekeys(1).get(0));

        BinWriter w = new BinWriter();
        ib.writeTo(w);
        Identity ib2 = Identity.readFrom(new BinReader(w.toByteArray()));
        assertEquals(ib.uid(), ib2.uid());

        ConversationManager ca = new ConversationManager(ia);
        ConversationManager cb2 = new ConversationManager(ib2);
        ca.startSession(ib.uid(), bundle);
        assertEquals("after restart", text(cb2.decrypt(ca.encryptFor(ib.uid(), bytes("after restart")))));
    }

    @Test
    void groupChainsSkippedKeysAndAdminRightsSurviveARestart() {
        Identity ia = Identity.generate();
        Identity ib = Identity.generate();
        GroupManager ga = new GroupManager(ia);
        GroupManager gb = new GroupManager(ib);
        GroupManager.GroupMember memberB = new GroupManager.GroupMember(ib.sigPub());

        GroupManager.CreatedGroup created = ga.createGroup(List.of(memberB));
        String gid = created.groupId();
        List<GroupManager.Outbound> follow = gb.handleControl(ia.uid(), created.outbound().get(0).plaintext());
        ga.handleControl(ib.uid(), follow.get(0).plaintext());

        byte[] g1 = ga.encrypt(gid, bytes("g1")).wire();
        byte[] g2 = ga.encrypt(gid, bytes("g2")).wire();
        byte[] g3 = ga.encrypt(gid, bytes("g3")).wire();
        assertEquals("g3", new String(gb.decrypt(g3).orElseThrow().plaintext(), UTF_8));

        GroupManager ga2 = restart(ia, ga);
        GroupManager gb2 = restart(ib, gb);

        assertEquals("g1", new String(gb2.decrypt(g1).orElseThrow().plaintext(), UTF_8));
        assertEquals("g2", new String(gb2.decrypt(g2).orElseThrow().plaintext(), UTF_8));
        assertEquals("g4", new String(gb2.decrypt(ga2.encrypt(gid, bytes("g4")).wire()).orElseThrow().plaintext(), UTF_8));
        assertEquals("h1", new String(ga2.decrypt(gb2.encrypt(gid, bytes("h1")).wire()).orElseThrow().plaintext(), UTF_8));

        assertDoesNotThrow(() -> ga2.updateMembers(gid, List.of(memberB)));
        assertThrows(IllegalStateException.class, () -> gb2.updateMembers(gid, List.of(memberB)));
    }

    @Test
    void corruptStateIsRejected() {
        assertThrows(IllegalArgumentException.class, () -> Identity.readFrom(new BinReader(new byte[] {1, 2, 3})));
        BinWriter w = new BinWriter();
        Identity.generate().writeTo(w);
        byte[] full = w.toByteArray();
        byte[] cut = java.util.Arrays.copyOf(full, full.length / 2);
        assertThrows(IllegalArgumentException.class, () -> Identity.readFrom(new BinReader(cut)));
    }
}

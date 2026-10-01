package org.nomad.crypto;

import static java.nio.charset.StandardCharsets.UTF_8;
import static org.junit.jupiter.api.Assertions.*;

import java.nio.ByteBuffer;
import java.util.ArrayList;
import java.util.HashMap;
import java.util.HexFormat;
import java.util.List;
import java.util.Map;
import java.util.Optional;
import org.junit.jupiter.api.Test;
import org.nomad.core.Ids;

class GroupManagerTest {
    private static final class Node {
        final Identity id = Identity.generate();
        final ConversationManager convo = new ConversationManager(id);
        final GroupManager groups = new GroupManager(id);
        final List<String> inbox = new ArrayList<>();
        final List<byte[]> held = new ArrayList<>();

        String uid() {
            return id.uid();
        }

        GroupManager.GroupMember member() {
            return new GroupManager.GroupMember(id.sigPub());
        }
    }

    private static final class World {
        final Map<String, Node> byUid = new HashMap<>();

        Node add() {
            Node n = new Node();
            byUid.put(n.uid(), n);
            return n;
        }

        /** Pairwise delivery of one control message; returns the follow-ups the receiver wants to send. */
        List<GroupManager.Outbound> receive(Node from, GroupManager.Outbound o) {
            Node to = byUid.get(o.toUid());
            if (!from.convo.hasSession(to.uid())) {
                from.convo.startSession(
                        to.uid(), to.id.publicBundle().withOpk(to.id.generateOneTimePrekeys(1).get(0)));
            }
            byte[] wire = from.convo.encryptFor(to.uid(), o.plaintext());
            Optional<ConversationManager.Decrypted> d = to.convo.decrypt(wire);
            assertTrue(d.isPresent(), "control message could not be decrypted");
            List<GroupManager.Outbound> follow = to.groups.handleControl(d.get().peerUid(), d.get().plaintext());
            retry(to);
            return follow;
        }

        /** Delivers a control message and everything that follows from it. */
        void control(Node from, GroupManager.Outbound o) {
            List<GroupManager.Outbound> follow = receive(from, o);
            sendAll(byUid.get(o.toUid()), follow);
        }

        void sendAll(Node from, List<GroupManager.Outbound> out) {
            for (GroupManager.Outbound o : out) {
                control(from, o);
            }
        }

        void group(Node to, byte[] wire) {
            Optional<GroupManager.GroupDecrypted> d = to.groups.decrypt(wire);
            if (d.isPresent()) {
                to.inbox.add(d.get().senderUid() + ":" + new String(d.get().plaintext(), UTF_8));
            } else {
                to.held.add(wire);
            }
        }

        GroupManager.Sealed broadcast(Node from, String gid, String text) {
            GroupManager.Sealed s = from.groups.encrypt(gid, text.getBytes(UTF_8));
            for (GroupManager.GroupMember m : s.recipients()) {
                group(byUid.get(m.uid()), s.wire());
            }
            return s;
        }

        void retry(Node n) {
            boolean progress = true;
            while (progress) {
                progress = false;
                for (byte[] w : new ArrayList<>(n.held)) {
                    Optional<GroupManager.GroupDecrypted> d = n.groups.decrypt(w);
                    if (d.isPresent()) {
                        n.held.remove(w);
                        n.inbox.add(d.get().senderUid() + ":" + new String(d.get().plaintext(), UTF_8));
                        progress = true;
                    }
                }
            }
        }
    }

    private static GroupManager.Outbound to(List<GroupManager.Outbound> list, Node n) {
        return list.stream().filter(o -> o.toUid().equals(n.uid())).findFirst().orElseThrow();
    }

    private static byte[] b(String s) {
        return s.getBytes(UTF_8);
    }

    private static String created(World w, Node admin, Node... others) {
        List<GroupManager.GroupMember> ms = new ArrayList<>();
        for (Node o : others) {
            ms.add(o.member());
        }
        GroupManager.CreatedGroup g = admin.groups.createGroup(ms);
        w.sendAll(admin, g.outbound());
        return g.groupId();
    }

    @Test
    void createAndChat() {
        World w = new World();
        Node a = w.add();
        Node bb = w.add();
        Node c = w.add();
        Node d = w.add();
        String gid = created(w, a, bb, c, d);

        for (Node n : List.of(a, bb, c, d)) {
            assertTrue(n.groups.isReady(gid), "not ready: everyone must know everyone's chain");
        }
        w.broadcast(a, gid, "hello");
        w.broadcast(bb, gid, "hi");
        for (Node n : List.of(bb, c, d)) {
            assertTrue(n.inbox.contains(a.uid() + ":hello"));
        }
        for (Node n : List.of(a, c, d)) {
            assertTrue(n.inbox.contains(bb.uid() + ":hi"));
        }
        assertFalse(a.inbox.contains(a.uid() + ":hello"));
    }

    @Test
    void messageBeforeKeysIsHeldAndRetried() {
        World w = new World();
        Node a = w.add();
        Node bb = w.add();
        Node c = w.add();
        GroupManager.CreatedGroup g = a.groups.createGroup(List.of(bb.member(), c.member()));
        String gid = g.groupId();

        List<GroupManager.Outbound> fromB = w.receive(a, to(g.outbound(), bb));
        w.control(bb, to(fromB, a));

        GroupManager.Sealed early = bb.groups.encrypt(gid, b("early"));
        w.group(a, early.wire());
        w.group(c, early.wire());
        assertEquals(1, c.held.size());
        assertTrue(c.inbox.isEmpty());

        w.control(bb, to(fromB, c));
        assertEquals(1, c.held.size());

        w.control(a, to(g.outbound(), c));
        assertTrue(c.held.isEmpty());
        assertTrue(c.inbox.contains(bb.uid() + ":early"));
        assertTrue(a.inbox.contains(bb.uid() + ":early"));
    }

    @Test
    void removedMemberCannotReadNewMessages() {
        World w = new World();
        Node a = w.add();
        Node bb = w.add();
        Node c = w.add();
        Node d = w.add();
        String gid = created(w, a, bb, c, d);
        w.broadcast(a, gid, "before");

        List<GroupManager.Outbound> update = a.groups.updateMembers(gid, List.of(bb.member(), c.member()));
        assertEquals(2, update.size());
        w.sendAll(a, update);

        GroupManager.Sealed after = w.broadcast(a, gid, "after");
        assertEquals(2, after.recipients().size());
        assertTrue(bb.inbox.contains(a.uid() + ":after"));
        assertTrue(c.inbox.contains(a.uid() + ":after"));
        assertTrue(bb.inbox.contains(a.uid() + ":before"));

        assertTrue(d.groups.decrypt(after.wire()).isEmpty());

        GroupManager.Sealed sneaky = d.groups.encrypt(gid, b("sneaky"));
        w.group(bb, sneaky.wire());
        assertFalse(bb.inbox.contains(d.uid() + ":sneaky"));
    }

    @Test
    void memberCanBeAddedLater() {
        World w = new World();
        Node a = w.add();
        Node bb = w.add();
        Node c = w.add();
        String gid = created(w, a, bb);
        w.broadcast(a, gid, "old news");

        w.sendAll(a, a.groups.updateMembers(gid, List.of(bb.member(), c.member())));
        assertTrue(c.groups.isReady(gid));
        w.broadcast(bb, gid, "welcome");
        assertTrue(c.inbox.contains(bb.uid() + ":welcome"));
        assertFalse(c.inbox.contains(a.uid() + ":old news"));
    }

    @Test
    void onlyTheAdminCanChangeMembers() {
        World w = new World();
        Node a = w.add();
        Node bb = w.add();
        Node c = w.add();
        String gid = created(w, a, bb, c);
        assertThrows(IllegalStateException.class, () -> bb.groups.updateMembers(gid, List.of(a.member())));
    }

    @Test
    void outOfOrderMessagesAreDecrypted() {
        World w = new World();
        Node a = w.add();
        Node bb = w.add();
        String gid = created(w, a, bb);
        byte[] m1 = a.groups.encrypt(gid, b("1")).wire();
        byte[] m2 = a.groups.encrypt(gid, b("2")).wire();
        byte[] m3 = a.groups.encrypt(gid, b("3")).wire();

        assertEquals("3", new String(bb.groups.decrypt(m3).orElseThrow().plaintext(), UTF_8));
        assertEquals("1", new String(bb.groups.decrypt(m1).orElseThrow().plaintext(), UTF_8));
        assertEquals("2", new String(bb.groups.decrypt(m2).orElseThrow().plaintext(), UTF_8));
    }

    @Test
    void replayIsRejected() {
        World w = new World();
        Node a = w.add();
        Node bb = w.add();
        String gid = created(w, a, bb);
        byte[] m = a.groups.encrypt(gid, b("once")).wire();
        assertTrue(bb.groups.decrypt(m).isPresent());
        assertTrue(bb.groups.decrypt(m).isEmpty());
    }

    @Test
    void tamperedCiphertextOrSignatureIsRejectedAndChainSurvives() {
        World w = new World();
        Node a = w.add();
        Node bb = w.add();
        String gid = created(w, a, bb);
        byte[] m = a.groups.encrypt(gid, b("intact")).wire();

        byte[] badCt = m.clone();
        badCt[16 + 4 + 1] ^= 0x01;
        assertTrue(bb.groups.decrypt(badCt).isEmpty());

        byte[] badSig = m.clone();
        badSig[badSig.length - 1] ^= 0x01;
        assertTrue(bb.groups.decrypt(badSig).isEmpty());

        assertEquals("intact", new String(bb.groups.decrypt(m).orElseThrow().plaintext(), UTF_8));
    }

    @Test
    void groupStateFromANonAdminIsIgnored() {
        World w = new World();
        Node a = w.add();
        Node bb = w.add();
        Node c = w.add();
        String gid = created(w, a, bb, c);

        // c pretends to be the admin and tells b that the group has a new generation without b
        byte[] spoof = ByteBuffer.allocate(1 + 16 + 4 + 2 + 1 + 32 * 2 + 16 + 32 + 4)
                .put(GroupManager.KIND_GROUP_STATE)
                .put(HexFormat.of().parseHex(gid))
                .putInt(99)
                .putShort((short) 0)
                .put((byte) 2)
                .put(a.id.sigPub())
                .put(c.id.sigPub())
                .put(new byte[16])
                .put(new byte[32])
                .putInt(0)
                .array();
        w.control(c, new GroupManager.Outbound(bb.uid(), Ids.mailboxIdFor(bb.id.sigPub()), spoof));

        GroupManager.Sealed s = w.broadcast(a, gid, "still fine");
        assertEquals(2, s.recipients().size());
        assertTrue(bb.inbox.contains(a.uid() + ":still fine"));
    }
}

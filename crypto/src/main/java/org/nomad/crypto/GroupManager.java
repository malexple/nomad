package org.nomad.crypto;

import java.nio.ByteBuffer;
import java.security.SecureRandom;
import java.util.ArrayList;
import java.util.HashMap;
import java.util.HashSet;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Optional;
import java.util.Set;
import org.nomad.core.BinReader;
import org.nomad.core.BinWriter;
import org.nomad.core.DeviceAuth;
import org.nomad.core.Ids;

/**
 * Group chats with Sender Keys (up to MAX_MEMBERS members, one admin).
 * - Every member has its own sending chain per generation (chainId, chain key, iteration).
 * - Chains are delivered to the other members through the pairwise sessions (control messages, see below).
 * - Group messages are padded to size buckets, encrypted once, signed with the sender's Ed25519 identity key
 *   and sent as one copy per member.
 * - Any membership change (admin only) raises the generation: all members create new chains, so a removed
 *   member cannot read new messages and is no longer accepted as a sender.
 * This class does no networking: it returns Outbound items that the caller sends through a ConversationManager.
 * Control plaintexts: 0x01 chat text (application), 0x10 GROUP_STATE, 0x11 SENDER_KEY.
 * Group wire: chainId(16) | iteration(4) | ciphertext+tag | signature(64).
 */
public final class GroupManager {
    public static final byte KIND_CHAT = 0x01;
    static final byte KIND_GROUP_STATE = 0x10;
    static final byte KIND_SENDER_KEY = 0x11;
    public static final int MAX_MEMBERS = 10;

    private static final int MAX_SKIP = 1000;
    private static final int MAX_STORED_SKIPPED = 2000;
    private static final int MAX_PENDING = 100;
    private static final byte[] INFO_GROUP = Bytes.ascii("nomad-group-msg-v0");
    private static final byte[] SIG_DOMAIN = Bytes.ascii("nomad-group-sig-v0");
    private static final byte[] ONE = {0x01};
    private static final byte[] TWO = {0x02};
    private static final SecureRandom RANDOM = new SecureRandom();

    public record GroupMember(byte[] sigKey) {
        public GroupMember {
            if (sigKey == null || sigKey.length != 32) {
                throw new IllegalArgumentException("member key must be 32 bytes");
            }
        }

        public String uid() {
            return Ids.uid(sigKey);
        }

        public String mailboxId() {
            return Ids.mailboxIdFor(sigKey);
        }
    }

    /** Send plaintext to a peer through the pairwise session (the caller encrypts it with ConversationManager). */
    public record Outbound(String toUid, String toMailbox, byte[] plaintext) {}

    public record CreatedGroup(String groupId, List<Outbound> outbound) {}

    /** One ciphertext, to be sent (as the same envelope payload) to every recipient's mailbox. */
    public record Sealed(byte[] wire, List<GroupMember> recipients) {}

    public record GroupDecrypted(String groupId, String senderUid, byte[] plaintext) {}

    private static final class SenderChain {
        final byte[] chainId;
        byte[] ck;
        int iteration;

        SenderChain() {
            chainId = new byte[16];
            ck = new byte[32];
            RANDOM.nextBytes(chainId);
            RANDOM.nextBytes(ck);
        }

        SenderChain(byte[] chainId, byte[] ck, int iteration) {
            this.chainId = chainId;
            this.ck = ck;
            this.iteration = iteration;
        }
    }

    private static final class ReceiveChain {
        final int generation;
        final String senderUid;
        final byte[] senderSigKey;
        byte[] ck;
        int next;
        final Map<Integer, byte[]> skipped = new LinkedHashMap<>();

        ReceiveChain(int generation, String senderUid, byte[] senderSigKey, byte[] ck, int next) {
            this.generation = generation;
            this.senderUid = senderUid;
            this.senderSigKey = senderSigKey;
            this.ck = ck;
            this.next = next;
        }

        /** Verifies nothing: the signature was checked before. Changes the state only on success. */
        byte[] open(int iteration, byte[] ct, byte[] aad) {
            if (iteration < 0) {
                throw new DecryptionException("bad iteration");
            }
            if (iteration < next) {
                byte[] mk = skipped.get(iteration);
                if (mk == null) {
                    throw new DecryptionException("replayed or too old");
                }
                byte[] pt = Aead.crypt(false, mk, INFO_GROUP, ct, aad);
                skipped.remove(iteration);
                return pt;
            }
            if (iteration - next > MAX_SKIP) {
                throw new DecryptionException("too many skipped messages");
            }
            Map<Integer, byte[]> gap = new LinkedHashMap<>();
            byte[] c = ck;
            int n = next;
            while (n < iteration) {
                gap.put(n, Hkdf.hmac(c, ONE));
                c = Hkdf.hmac(c, TWO);
                n++;
            }
            byte[] mk = Hkdf.hmac(c, ONE);
            byte[] nextCk = Hkdf.hmac(c, TWO);
            byte[] pt = Aead.crypt(false, mk, INFO_GROUP, ct, aad);
            skipped.putAll(gap);
            while (skipped.size() > MAX_STORED_SKIPPED) {
                skipped.remove(skipped.keySet().iterator().next());
            }
            ck = nextCk;
            next = iteration + 1;
            return pt;
        }
    }

    private static final class Group {
        final byte[] groupId;
        final String groupHex;
        String adminUid;
        int generation;
        final LinkedHashMap<String, byte[]> members = new LinkedHashMap<>();
        SenderChain mine;
        final Map<String, ReceiveChain> recv = new HashMap<>();

        Group(byte[] groupId) {
            this.groupId = groupId;
            this.groupHex = Bytes.hex(groupId);
        }
    }

    private record PendingKey(String fromUid, byte[] plaintext) {}

    private final Identity me;
    private final Map<String, Group> groups = new HashMap<>();
    private final Map<String, List<PendingKey>> pending = new HashMap<>();

    public GroupManager(Identity me) {
        this.me = me;
    }

    // ---------------------------------------------------------------- admin operations

    public synchronized CreatedGroup createGroup(List<GroupMember> others) {
        checkMembers(others);
        byte[] gid = new byte[16];
        RANDOM.nextBytes(gid);
        Group g = new Group(gid);
        g.adminUid = me.uid();
        g.generation = 1;
        g.members.put(me.uid(), me.sigPub());
        for (GroupMember m : others) {
            g.members.put(m.uid(), m.sigKey());
        }
        g.mine = new SenderChain();
        groups.put(g.groupHex, g);
        return new CreatedGroup(g.groupHex, broadcastState(g));
    }

    /** Admin only: sets the list of other members (adds and removes) and rotates all sender keys. */
    public synchronized List<Outbound> updateMembers(String groupId, List<GroupMember> others) {
        Group g = groups.get(groupId);
        if (g == null) {
            throw new IllegalArgumentException("unknown group");
        }
        if (!g.adminUid.equals(me.uid())) {
            throw new IllegalStateException("only the admin can change the members");
        }
        checkMembers(others);
        g.generation++;
        g.members.clear();
        g.members.put(me.uid(), me.sigPub());
        for (GroupMember m : others) {
            g.members.put(m.uid(), m.sigKey());
        }
        g.mine = new SenderChain();
        prune(g);
        return broadcastState(g);
    }

    private void checkMembers(List<GroupMember> others) {
        if (others.isEmpty() || others.size() > MAX_MEMBERS - 1) {
            throw new IllegalArgumentException("a group needs 1.." + (MAX_MEMBERS - 1) + " other members");
        }
        Set<String> seen = new HashSet<>();
        seen.add(me.uid());
        for (GroupMember m : others) {
            if (!seen.add(m.uid())) {
                throw new IllegalArgumentException("duplicate member");
            }
        }
    }

    private List<Outbound> broadcastState(Group g) {
        byte[] state = encodeState(g);
        List<Outbound> out = new ArrayList<>();
        for (Map.Entry<String, byte[]> e : g.members.entrySet()) {
            if (!e.getKey().equals(me.uid())) {
                out.add(new Outbound(e.getKey(), Ids.mailboxIdFor(e.getValue()), state));
            }
        }
        return out;
    }

    // ---------------------------------------------------------------- control messages

    /**
     * Processes a pairwise-decrypted control plaintext. fromUid is authenticated by the pairwise session.
     * Returns follow-up control messages to send (our own sender key to the other members).
     */
    public synchronized List<Outbound> handleControl(String fromUid, byte[] plaintext) {
        try {
            if (plaintext.length == 0) {
                return List.of();
            }
            if (plaintext[0] == KIND_GROUP_STATE) {
                return onState(fromUid, plaintext);
            }
            if (plaintext[0] == KIND_SENDER_KEY) {
                return onSenderKey(fromUid, plaintext);
            }
            return List.of();
        } catch (RuntimeException e) {
            return List.of();
        }
    }

    private List<Outbound> onState(String fromUid, byte[] plain) {
        ByteBuffer b = ByteBuffer.wrap(plain, 1, plain.length - 1);
        byte[] gid = new byte[16];
        b.get(gid);
        int gen = b.getInt();
        int count = b.get() & 0xff;
        if (count < 2 || count > MAX_MEMBERS) {
            return List.of();
        }
        LinkedHashMap<String, byte[]> members = new LinkedHashMap<>();
        for (int i = 0; i < count; i++) {
            byte[] k = new byte[32];
            b.get(k);
            members.put(Ids.uid(k), k);
        }
        byte[] chainId = new byte[16];
        byte[] ck = new byte[32];
        b.get(chainId);
        b.get(ck);
        int iteration = b.getInt();

        String hex = Bytes.hex(gid);
        Group g = groups.get(hex);
        if (g != null) {
            if (!fromUid.equals(g.adminUid) || gen <= g.generation) {
                return List.of();
            }
            if (!members.containsKey(me.uid())) {
                groups.remove(hex);
                return List.of();
            }
        } else {
            if (!members.containsKey(me.uid()) || !members.containsKey(fromUid)) {
                return List.of();
            }
            g = new Group(gid);
            g.adminUid = fromUid;
            groups.put(hex, g);
        }
        if (!members.containsKey(fromUid)) {
            return List.of();
        }
        g.generation = gen;
        g.members.clear();
        g.members.putAll(members);
        prune(g);
        addChain(g, fromUid, chainId, ck, iteration, gen);
        g.mine = new SenderChain();

        List<Outbound> out = new ArrayList<>();
        byte[] senderKey = encodeSenderKey(g);
        for (Map.Entry<String, byte[]> e : g.members.entrySet()) {
            if (!e.getKey().equals(me.uid())) {
                out.add(new Outbound(e.getKey(), Ids.mailboxIdFor(e.getValue()), senderKey));
            }
        }
        List<PendingKey> waiting = pending.remove(hex);
        if (waiting != null) {
            for (PendingKey p : waiting) {
                out.addAll(handleControl(p.fromUid(), p.plaintext()));
            }
        }
        return out;
    }

    private List<Outbound> onSenderKey(String fromUid, byte[] plain) {
        ByteBuffer b = ByteBuffer.wrap(plain, 1, plain.length - 1);
        byte[] gid = new byte[16];
        b.get(gid);
        int gen = b.getInt();
        byte[] chainId = new byte[16];
        byte[] ck = new byte[32];
        b.get(chainId);
        b.get(ck);
        int iteration = b.getInt();

        String hex = Bytes.hex(gid);
        Group g = groups.get(hex);
        if (g == null || gen > g.generation) {
            List<PendingKey> list = pending.computeIfAbsent(hex, k -> new ArrayList<>());
            if (list.size() < MAX_PENDING) {
                list.add(new PendingKey(fromUid, plain));
            }
            return List.of();
        }
        if (gen < g.generation || !g.members.containsKey(fromUid)) {
            return List.of();
        }
        addChain(g, fromUid, chainId, ck, iteration, gen);
        return List.of();
    }

    private void addChain(Group g, String senderUid, byte[] chainId, byte[] ck, int iteration, int gen) {
        g.recv.put(Bytes.hex(chainId), new ReceiveChain(gen, senderUid, g.members.get(senderUid), ck, iteration));
    }

    /** Keeps the chains of the previous generation for messages that were already in flight. */
    private void prune(Group g) {
        g.recv.values().removeIf(c -> c.generation < g.generation - 1);
    }

    private byte[] encodeState(Group g) {
        ByteBuffer b = ByteBuffer.allocate(1 + 16 + 4 + 1 + 32 * g.members.size() + 16 + 32 + 4);
        b.put(KIND_GROUP_STATE).put(g.groupId).putInt(g.generation).put((byte) g.members.size());
        for (byte[] k : g.members.values()) {
            b.put(k);
        }
        b.put(g.mine.chainId).put(g.mine.ck).putInt(g.mine.iteration);
        return b.array();
    }

    private byte[] encodeSenderKey(Group g) {
        return ByteBuffer.allocate(1 + 16 + 4 + 16 + 32 + 4)
                .put(KIND_SENDER_KEY)
                .put(g.groupId)
                .putInt(g.generation)
                .put(g.mine.chainId)
                .put(g.mine.ck)
                .putInt(g.mine.iteration)
                .array();
    }

    // ---------------------------------------------------------------- group messages

    public synchronized boolean canSend(String groupId) {
        Group g = groups.get(groupId);
        return g != null && g.mine != null;
    }

    /** True when the chains of all other members of the current generation are known. */
    public synchronized boolean isReady(String groupId) {
        Group g = groups.get(groupId);
        if (g == null || g.mine == null) {
            return false;
        }
        int known = 0;
        for (ReceiveChain c : g.recv.values()) {
            if (c.generation == g.generation && !c.senderUid.equals(me.uid())) {
                known++;
            }
        }
        return known == g.members.size() - 1;
    }

    public synchronized Sealed encrypt(String groupId, byte[] plaintext) {
        Group g = groups.get(groupId);
        if (g == null || g.mine == null) {
            throw new IllegalStateException("no sender key for this group yet");
        }
        SenderChain c = g.mine;
        byte[] mk = Hkdf.hmac(c.ck, ONE);
        c.ck = Hkdf.hmac(c.ck, TWO);
        int iteration = c.iteration++;
        byte[] aad = Bytes.concat(g.groupId, c.chainId, Bytes.intBE(iteration));
        byte[] ct = Aead.crypt(true, mk, INFO_GROUP, Padding.pad(plaintext), aad);
        byte[] sig = DeviceAuth.signBytes(
                me.deviceKeys().priv(),
                Bytes.concat(SIG_DOMAIN, g.groupId, c.chainId, Bytes.intBE(iteration), ct));
        byte[] wire = Bytes.concat(c.chainId, Bytes.intBE(iteration), ct, sig);
        List<GroupMember> recipients = new ArrayList<>();
        for (Map.Entry<String, byte[]> e : g.members.entrySet()) {
            if (!e.getKey().equals(me.uid())) {
                recipients.add(new GroupMember(e.getValue()));
            }
        }
        return new Sealed(wire, recipients);
    }

    /** Empty if the chain is not known (yet), the signature or the tag is bad, or the sender is no longer a member. */
    public synchronized Optional<GroupDecrypted> decrypt(byte[] wire) {
        if (wire.length < 16 + 4 + 16 + 64) {
            return Optional.empty();
        }
        try {
            byte[] chainId = java.util.Arrays.copyOfRange(wire, 0, 16);
            int iteration = Bytes.readInt(wire, 16);
            byte[] ct = java.util.Arrays.copyOfRange(wire, 20, wire.length - 64);
            byte[] sig = java.util.Arrays.copyOfRange(wire, wire.length - 64, wire.length);
            String chainHex = Bytes.hex(chainId);
            for (Group g : groups.values()) {
                ReceiveChain c = g.recv.get(chainHex);
                if (c == null) {
                    continue;
                }
                if (!g.members.containsKey(c.senderUid)) {
                    return Optional.empty();
                }
                byte[] signed = Bytes.concat(SIG_DOMAIN, g.groupId, chainId, Bytes.intBE(iteration), ct);
                if (!DeviceAuth.verifyBytes(c.senderSigKey, signed, sig)) {
                    return Optional.empty();
                }
                byte[] aad = Bytes.concat(g.groupId, chainId, Bytes.intBE(iteration));
                byte[] pt = Padding.unpad(c.open(iteration, ct, aad));
                return Optional.of(new GroupDecrypted(g.groupHex, c.senderUid, pt));
            }
            return Optional.empty();
        } catch (RuntimeException e) {
            return Optional.empty();
        }
    }

    // ---------------------------------------------------------------- persistence

    public synchronized void writeTo(BinWriter w) {
        w.i32(groups.size());
        for (Group g : groups.values()) {
            w.bytes(g.groupId).str(g.adminUid).i32(g.generation);
            w.i32(g.members.size());
            for (byte[] key : g.members.values()) {
                w.bytes(key);
            }
            w.bool(g.mine != null);
            if (g.mine != null) {
                w.bytes(g.mine.chainId).bytes(g.mine.ck).i32(g.mine.iteration);
            }
            w.i32(g.recv.size());
            for (Map.Entry<String, ReceiveChain> e : g.recv.entrySet()) {
                ReceiveChain c = e.getValue();
                w.str(e.getKey()).i32(c.generation).bytes(c.senderSigKey).bytes(c.ck).i32(c.next);
                w.i32(c.skipped.size());
                for (Map.Entry<Integer, byte[]> s : c.skipped.entrySet()) {
                    w.i32(s.getKey()).bytes(s.getValue());
                }
            }
        }
        w.i32(pending.size());
        for (Map.Entry<String, List<PendingKey>> e : pending.entrySet()) {
            w.str(e.getKey()).i32(e.getValue().size());
            for (PendingKey p : e.getValue()) {
                w.str(p.fromUid()).bytes(p.plaintext());
            }
        }
    }

    public static GroupManager readFrom(Identity me, BinReader r) {
        GroupManager m = new GroupManager(me);
        int groupCount = r.count();
        for (int i = 0; i < groupCount; i++) {
            Group g = new Group(r.bytes());
            g.adminUid = r.str();
            g.generation = r.i32();
            int members = r.count();
            for (int j = 0; j < members; j++) {
                byte[] key = r.bytes();
                g.members.put(Ids.uid(key), key);
            }
            if (r.bool()) {
                byte[] chainId = r.bytes();
                byte[] ck = r.bytes();
                g.mine = new SenderChain(chainId, ck, r.i32());
            }
            int chains = r.count();
            for (int j = 0; j < chains; j++) {
                String chainHex = r.str();
                int generation = r.i32();
                byte[] sigKey = r.bytes();
                byte[] ck = r.bytes();
                ReceiveChain c = new ReceiveChain(generation, Ids.uid(sigKey), sigKey, ck, r.i32());
                int skipped = r.count();
                for (int k = 0; k < skipped; k++) {
                    int iteration = r.i32();
                    c.skipped.put(iteration, r.bytes());
                }
                g.recv.put(chainHex, c);
            }
            m.groups.put(g.groupHex, g);
        }
        int pendingGroups = r.count();
        for (int i = 0; i < pendingGroups; i++) {
            String hex = r.str();
            int n = r.count();
            List<PendingKey> list = new ArrayList<>();
            for (int j = 0; j < n; j++) {
                String from = r.str();
                list.add(new PendingKey(from, r.bytes()));
            }
            m.pending.put(hex, list);
        }
        return m;
    }
}

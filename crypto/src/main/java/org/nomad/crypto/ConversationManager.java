package org.nomad.crypto;

import java.util.ArrayList;
import java.util.HashMap;
import java.util.List;
import java.util.Map;
import java.util.Optional;
import org.nomad.core.BinReader;
import org.nomad.core.BinWriter;
import org.nomad.core.Ids;
import org.nomad.core.PrekeyBundle;

/**
 * All sessions of one device. Several sessions per peer are allowed (both sides may start a conversation
 * at the same time); the most recently used one sends. Incoming messages do not carry a sender id, so
 * "normal" messages are tried against the sessions (the failed attempts leave no trace).
 * Plaintexts are padded to size buckets before encryption (see Padding).
 */
public final class ConversationManager {
    /** peerSigKey is the Ed25519 identity key of the sender (the mailbox of the peer is derived from it). */
    public record Decrypted(String peerUid, byte[] peerSigKey, byte[] plaintext) {}

    private final Identity me;
    private final Map<String, List<RatchetSession>> byPeer = new HashMap<>();

    public ConversationManager(Identity me) {
        this.me = me;
    }

    public synchronized boolean hasSession(String peerUid) {
        List<RatchetSession> l = byPeer.get(peerUid);
        if (l == null) {
            return false;
        }
        for (RatchetSession s : l) {
            if (s.canSend()) {
                return true;
            }
        }
        return false;
    }

    public synchronized void startSession(String peerUid, PrekeyBundle bundle) {
        if (!bundle.uid().equals(peerUid)) {
            throw new SecurityException("bundle does not belong to " + peerUid);
        }
        RatchetSession s = RatchetSession.initiate(me, bundle);
        byPeer.computeIfAbsent(peerUid, k -> new ArrayList<>()).add(0, s);
    }

    public synchronized byte[] encryptFor(String peerUid, byte[] plaintext) {
        List<RatchetSession> l = byPeer.get(peerUid);
        if (l != null) {
            for (RatchetSession s : l) {
                if (s.canSend()) {
                    return s.encrypt(Padding.pad(plaintext));
                }
            }
        }
        throw new IllegalStateException("no session with " + peerUid);
    }

    public synchronized Optional<Decrypted> decrypt(byte[] wire) {
        if (wire.length < 2) {
            return Optional.empty();
        }
        try {
            if (wire[0] == RatchetSession.TYPE_INITIAL) {
                return decryptInitial(wire);
            }
            for (List<RatchetSession> l : byPeer.values()) {
                for (RatchetSession s : new ArrayList<>(l)) {
                    try {
                        byte[] pt = Padding.unpad(s.decrypt(wire));
                        l.remove(s);
                        l.add(0, s);
                        return Optional.of(new Decrypted(s.peerUid(), s.peerSigKey(), pt));
                    } catch (DecryptionException e) {
                        // not this session
                    }
                }
            }
            return Optional.empty();
        } catch (RuntimeException e) {
            return Optional.empty();
        }
    }

    private Optional<Decrypted> decryptInitial(byte[] wire) {
        InitialHeader h = InitialHeader.parse(wire, 1);
        String peer = Ids.uid(h.sigKeyA());
        List<RatchetSession> known = byPeer.get(peer);
        if (known != null) {
            for (RatchetSession s : known) {
                if (s.matchesHandshake(h.ekA())) {
                    byte[] pt = Padding.unpad(s.decrypt(wire));
                    known.remove(s);
                    known.add(0, s);
                    return Optional.of(new Decrypted(peer, s.peerSigKey(), pt));
                }
            }
        }
        RatchetSession fresh = RatchetSession.accept(me, h);
        byte[] pt = Padding.unpad(fresh.decrypt(wire));
        // authenticated: only now the one-time prekey is spent and the session is stored
        if (h.opkId() >= 0) {
            me.discardOneTimePrekey(h.opkId());
        }
        byPeer.computeIfAbsent(peer, k -> new ArrayList<>()).add(0, fresh);
        return Optional.of(new Decrypted(peer, fresh.peerSigKey(), pt));
    }

    // ---------------------------------------------------------------- persistence

    public synchronized void writeTo(BinWriter w) {
        w.i32(byPeer.size());
        for (Map.Entry<String, List<RatchetSession>> e : byPeer.entrySet()) {
            w.str(e.getKey()).i32(e.getValue().size());
            for (RatchetSession s : e.getValue()) {
                s.writeTo(w);
            }
        }
    }

    public static ConversationManager readFrom(Identity me, BinReader r) {
        ConversationManager m = new ConversationManager(me);
        int peers = r.count();
        for (int i = 0; i < peers; i++) {
            String peer = r.str();
            int n = r.count();
            List<RatchetSession> l = new ArrayList<>();
            for (int j = 0; j < n; j++) {
                l.add(RatchetSession.readFrom(r));
            }
            m.byPeer.put(peer, l);
        }
        return m;
    }
}

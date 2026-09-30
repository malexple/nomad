package org.nomad.crypto;

import java.security.MessageDigest;
import java.util.Arrays;
import org.nomad.core.Ids;
import org.nomad.core.PrekeyBundle;

/**
 * One X3DH + Double Ratchet session with one peer device (scheme version 1).
 * Wire: type (1) | [initial header, only while we are the initiator and have not heard back] | ratchet message.
 */
final class RatchetSession implements E2eeSession {
    static final byte TYPE_INITIAL = 1;
    static final byte TYPE_NORMAL = 2;

    private final String peerUid;
    private final DoubleRatchet ratchet;
    private final byte[] handshakeId;
    private byte[] pendingInitialHeader;

    private RatchetSession(String peerUid, DoubleRatchet ratchet, byte[] handshakeId, byte[] pendingInitialHeader) {
        this.peerUid = peerUid;
        this.ratchet = ratchet;
        this.handshakeId = handshakeId;
        this.pendingInitialHeader = pendingInitialHeader;
    }

    static RatchetSession initiate(Identity me, PrekeyBundle peer) {
        X3dh.InitiatorResult r = X3dh.initiate(me, peer);
        DoubleRatchet dr = DoubleRatchet.initAlice(r.sk(), peer.spk(), r.ad());
        InitialHeader h = new InitialHeader(
                me.sigPub(), me.identityDhPub(), me.sigIkDh(), r.ek(), r.spkId(), r.opkId());
        return new RatchetSession(peer.uid(), dr, r.ek(), h.encode());
    }

    static RatchetSession accept(Identity me, InitialHeader h) {
        X3dh.ResponderResult r = X3dh.respond(me, h);
        DoubleRatchet dr = DoubleRatchet.initBob(r.sk(), r.spk(), r.ad());
        return new RatchetSession(Ids.uid(h.sigKeyA()), dr, h.ekA(), null);
    }

    String peerUid() {
        return peerUid;
    }

    boolean matchesHandshake(byte[] ek) {
        return MessageDigest.isEqual(handshakeId, ek);
    }

    boolean canSend() {
        return ratchet.canSend();
    }

    @Override
    public int schemeVersion() {
        return 1;
    }

    @Override
    public synchronized byte[] encrypt(byte[] plaintext) {
        byte[] body = ratchet.encrypt(plaintext);
        if (pendingInitialHeader != null) {
            return Bytes.concat(new byte[] {TYPE_INITIAL}, pendingInitialHeader, body);
        }
        return Bytes.concat(new byte[] {TYPE_NORMAL}, body);
    }

    @Override
    public synchronized byte[] decrypt(byte[] wire) {
        if (wire.length < 2) {
            throw new DecryptionException("short message");
        }
        int off = 1;
        if (wire[0] == TYPE_INITIAL) {
            off += InitialHeader.SIZE;
        } else if (wire[0] != TYPE_NORMAL) {
            throw new DecryptionException("unknown message type");
        }
        if (off >= wire.length) {
            throw new DecryptionException("short message");
        }
        byte[] plaintext = ratchet.decrypt(Arrays.copyOfRange(wire, off, wire.length));
        pendingInitialHeader = null;
        return plaintext;
    }
}

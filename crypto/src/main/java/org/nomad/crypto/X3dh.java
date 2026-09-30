package org.nomad.crypto;

import java.util.Arrays;
import org.nomad.core.DeviceAuth;
import org.nomad.core.PrekeyBundle;

/**
 * X3DH as in the Signal specification, but with separate Ed25519 / X25519 identity keys.
 * SK = HKDF(salt = 32 zero bytes, ikm = 0xFF*32 || DH1 || DH2 || DH3 [|| DH4], info = "nomad-x3dh-v0").
 * AD = ikDhA || ikDhB || sigKeyA || sigKeyB (binds both the DH and the signing identities).
 * respond() does not consume the one-time prekey: the caller discards it after the first message is authenticated.
 */
final class X3dh {
    private static final byte[] INFO = Bytes.ascii("nomad-x3dh-v0");
    private static final byte[] F = filled(32, (byte) 0xFF);

    private X3dh() {}

    record InitiatorResult(byte[] sk, byte[] ad, byte[] ek, int spkId, int opkId) {}

    record ResponderResult(byte[] sk, byte[] ad, X25519Keys.Pair spk) {}

    static InitiatorResult initiate(Identity alice, PrekeyBundle bob) {
        if (!bob.verifySignatures()) {
            throw new SecurityException("bad prekey bundle signature");
        }
        X25519Keys.Pair ek = X25519Keys.generate();
        byte[] dh1 = X25519Keys.dh(alice.identityDh().priv(), bob.spk());
        byte[] dh2 = X25519Keys.dh(ek.priv(), bob.ikDh());
        byte[] dh3 = X25519Keys.dh(ek.priv(), bob.spk());
        byte[] dh4 = bob.opk() != null ? X25519Keys.dh(ek.priv(), bob.opk().pub()) : new byte[0];
        byte[] sk = kdf(dh1, dh2, dh3, dh4);
        byte[] ad = Bytes.concat(alice.identityDhPub(), bob.ikDh(), alice.sigPub(), bob.sigKey());
        return new InitiatorResult(sk, ad, ek.pub(), bob.spkId(), bob.opk() != null ? bob.opk().id() : -1);
    }

    static ResponderResult respond(Identity bob, InitialHeader h) {
        if (!DeviceAuth.verifyBytes(h.sigKeyA(), PrekeyBundle.ikMessage(h.ikDhA()), h.sigIkDhA())) {
            throw new SecurityException("bad identity key signature");
        }
        X25519Keys.Pair spk = bob.signedPrekey(h.spkId());
        if (spk == null) {
            throw new IllegalArgumentException("unknown signed prekey");
        }
        X25519Keys.Pair opk = null;
        if (h.opkId() >= 0) {
            opk = bob.oneTimePrekey(h.opkId());
            if (opk == null) {
                throw new IllegalArgumentException("unknown or already used one-time prekey");
            }
        }
        byte[] dh1 = X25519Keys.dh(spk.priv(), h.ikDhA());
        byte[] dh2 = X25519Keys.dh(bob.identityDh().priv(), h.ekA());
        byte[] dh3 = X25519Keys.dh(spk.priv(), h.ekA());
        byte[] dh4 = opk != null ? X25519Keys.dh(opk.priv(), h.ekA()) : new byte[0];
        byte[] sk = kdf(dh1, dh2, dh3, dh4);
        byte[] ad = Bytes.concat(h.ikDhA(), bob.identityDhPub(), h.sigKeyA(), bob.sigPub());
        return new ResponderResult(sk, ad, spk);
    }

    private static byte[] kdf(byte[] dh1, byte[] dh2, byte[] dh3, byte[] dh4) {
        return Hkdf.derive(new byte[32], Bytes.concat(F, dh1, dh2, dh3, dh4), INFO, 32);
    }

    private static byte[] filled(int n, byte v) {
        byte[] b = new byte[n];
        Arrays.fill(b, v);
        return b;
    }
}

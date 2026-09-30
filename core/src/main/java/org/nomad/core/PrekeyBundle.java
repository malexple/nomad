package org.nomad.core;

import java.nio.ByteBuffer;
import java.nio.charset.StandardCharsets;
import java.security.PublicKey;

/**
 * Public keys of one device. Two separate key pairs (not XEdDSA):
 * sigKey = Ed25519 identity (uid = hash of it), ikDh = X25519 identity key for X3DH.
 * The X25519 keys are signed by the Ed25519 key, so a node cannot substitute them undetected.
 */
public record PrekeyBundle(
        byte[] sigKey, byte[] ikDh, byte[] sigIkDh, int spkId, byte[] spk, byte[] sigSpk, OneTimePrekey opk) {

    private static final byte[] IK_DOMAIN = "nomad-ik-v0".getBytes(StandardCharsets.US_ASCII);
    private static final byte[] SPK_DOMAIN = "nomad-spk-v0".getBytes(StandardCharsets.US_ASCII);

    public PrekeyBundle {
        need(sigKey, 32, "sigKey");
        need(ikDh, 32, "ikDh");
        need(sigIkDh, 64, "sigIkDh");
        need(spk, 32, "spk");
        need(sigSpk, 64, "sigSpk");
    }

    private static void need(byte[] v, int len, String name) {
        if (v == null || v.length != len) {
            throw new IllegalArgumentException(name + " must be " + len + " bytes");
        }
    }

    public static byte[] ikMessage(byte[] ikDh) {
        return ByteBuffer.allocate(IK_DOMAIN.length + ikDh.length).put(IK_DOMAIN).put(ikDh).array();
    }

    public static byte[] spkMessage(int spkId, byte[] spk) {
        return ByteBuffer.allocate(SPK_DOMAIN.length + 4 + spk.length)
                .put(SPK_DOMAIN)
                .putInt(spkId)
                .put(spk)
                .array();
    }

    public String uid() {
        return Ids.uid(sigKey);
    }

    public PrekeyBundle withOpk(OneTimePrekey o) {
        return new PrekeyBundle(sigKey, ikDh, sigIkDh, spkId, spk, sigSpk, o);
    }

    public boolean verifySignatures() {
        try {
            PublicKey k = DeviceAuth.publicKeyFromRaw(sigKey);
            return DeviceAuth.verifyBytes(k, ikMessage(ikDh), sigIkDh)
                    && DeviceAuth.verifyBytes(k, spkMessage(spkId, spk), sigSpk);
        } catch (IllegalArgumentException e) {
            return false;
        }
    }
}

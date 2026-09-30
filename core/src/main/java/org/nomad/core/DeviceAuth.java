package org.nomad.core;

import java.nio.charset.StandardCharsets;
import java.security.SecureRandom;
import java.util.Locale;
import org.bouncycastle.crypto.params.Ed25519PrivateKeyParameters;
import org.bouncycastle.crypto.params.Ed25519PublicKeyParameters;
import org.bouncycastle.crypto.signers.Ed25519Signer;

/** Ed25519 (RFC 8032, pure) on raw key bytes. Signatures are 64 bytes. */
public final class DeviceAuth {
    private static final SecureRandom RANDOM = new SecureRandom();

    private DeviceAuth() {}

    public static DeviceKeyPair generateKeyPair() {
        Ed25519PrivateKeyParameters priv = new Ed25519PrivateKeyParameters(RANDOM);
        return new DeviceKeyPair(priv.getEncoded(), priv.generatePublicKey().getEncoded());
    }

    /** Restores a key pair from a stored private seed. */
    public static DeviceKeyPair fromPrivate(byte[] seed) {
        Ed25519PrivateKeyParameters priv = new Ed25519PrivateKeyParameters(seed, 0);
        return new DeviceKeyPair(priv.getEncoded(), priv.generatePublicKey().getEncoded());
    }

    /** Canonical string that is signed: METHOD, path+query, unix seconds, SHA-256(body). */
    public static String canonical(String method, String pathAndQuery, long timestamp, byte[] body) {
        return method.toUpperCase(Locale.ROOT) + "\n" + pathAndQuery + "\n" + timestamp + "\n"
                + Hex.encode(Ids.sha256(body));
    }

    public static byte[] signBytes(byte[] privateKey, byte[] data) {
        try {
            Ed25519Signer signer = new Ed25519Signer();
            signer.init(true, new Ed25519PrivateKeyParameters(privateKey, 0));
            signer.update(data, 0, data.length);
            return signer.generateSignature();
        } catch (Exception e) {
            throw new IllegalStateException("signing failed", e);
        }
    }

    public static boolean verifyBytes(byte[] publicKey, byte[] data, byte[] signature) {
        if (publicKey == null || publicKey.length != 32 || signature == null || signature.length != 64) {
            return false;
        }
        try {
            Ed25519Signer signer = new Ed25519Signer();
            signer.init(false, new Ed25519PublicKeyParameters(publicKey, 0));
            signer.update(data, 0, data.length);
            return signer.verifySignature(signature);
        } catch (RuntimeException e) {
            return false;
        }
    }

    public static byte[] sign(byte[] privateKey, String canonical) {
        return signBytes(privateKey, canonical.getBytes(StandardCharsets.UTF_8));
    }

    public static boolean verify(byte[] publicKey, String canonical, byte[] signature) {
        return verifyBytes(publicKey, canonical.getBytes(StandardCharsets.UTF_8), signature);
    }
}

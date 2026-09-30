package org.nomad.crypto;

import java.security.KeyPair;
import java.util.ArrayList;
import java.util.HashMap;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import org.nomad.core.DeviceAuth;
import org.nomad.core.Ids;
import org.nomad.core.OneTimePrekey;
import org.nomad.core.PrekeyBundle;

/**
 * Key material of one device: the Ed25519 identity (signing, uid), the X25519 identity key,
 * a signed prekey (the last two are kept) and one-time prekeys. Private parts never leave this object.
 */
public final class Identity {
    private final KeyPair sig;
    private final X25519Keys.Pair ik;
    private final byte[] sigIkDh;
    private final Map<Integer, X25519Keys.Pair> spks = new LinkedHashMap<>();
    private int currentSpkId;
    private byte[] currentSigSpk;
    private final Map<Integer, X25519Keys.Pair> opks = new HashMap<>();
    private int nextOpkId = 1;

    public Identity(KeyPair sigKeyPair) {
        this.sig = sigKeyPair;
        this.ik = X25519Keys.generate();
        this.sigIkDh = DeviceAuth.signBytes(sig.getPrivate(), PrekeyBundle.ikMessage(ik.pub()));
        rotateSignedPrekey();
    }

    public static Identity generate() {
        return new Identity(DeviceAuth.generateKeyPair());
    }

    public synchronized void rotateSignedPrekey() {
        currentSpkId++;
        X25519Keys.Pair p = X25519Keys.generate();
        spks.put(currentSpkId, p);
        currentSigSpk = DeviceAuth.signBytes(sig.getPrivate(), PrekeyBundle.spkMessage(currentSpkId, p.pub()));
        while (spks.size() > 2) {
            spks.remove(spks.keySet().iterator().next());
        }
    }

    /** Public identity part without a one-time prekey (what is uploaded to the directory). */
    public synchronized PrekeyBundle publicBundle() {
        X25519Keys.Pair p = spks.get(currentSpkId);
        return new PrekeyBundle(sigPub(), ik.pub(), sigIkDh, currentSpkId, p.pub(), currentSigSpk, null);
    }

    public synchronized List<OneTimePrekey> generateOneTimePrekeys(int n) {
        List<OneTimePrekey> out = new ArrayList<>();
        for (int i = 0; i < n; i++) {
            int id = nextOpkId++;
            X25519Keys.Pair p = X25519Keys.generate();
            opks.put(id, p);
            out.add(new OneTimePrekey(id, p.pub()));
        }
        return out;
    }

    synchronized X25519Keys.Pair signedPrekey(int id) {
        return spks.get(id);
    }

    /** Looks a one-time prekey up WITHOUT removing it (a forged first message must not burn it). */
    synchronized X25519Keys.Pair oneTimePrekey(int id) {
        return opks.get(id);
    }

    /** One-time prekeys are single-use: call this only after the first message was authenticated. */
    synchronized void discardOneTimePrekey(int id) {
        opks.remove(id);
    }

    X25519Keys.Pair identityDh() {
        return ik;
    }

    byte[] identityDhPub() {
        return ik.pub();
    }

    byte[] sigIkDh() {
        return sigIkDh;
    }

    public byte[] sigPub() {
        return DeviceAuth.rawPublicKey(sig.getPublic());
    }

    public String uid() {
        return Ids.uid(sigPub());
    }

    public KeyPair sigKeyPair() {
        return sig;
    }
}

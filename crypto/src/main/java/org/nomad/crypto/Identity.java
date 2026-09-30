package org.nomad.crypto;

import java.util.ArrayList;
import java.util.HashMap;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import org.nomad.core.BinReader;
import org.nomad.core.BinWriter;
import org.nomad.core.DeviceAuth;
import org.nomad.core.DeviceKeyPair;
import org.nomad.core.Ids;
import org.nomad.core.OneTimePrekey;
import org.nomad.core.PrekeyBundle;

/**
 * Key material of one device: the Ed25519 identity (signing, uid), the X25519 identity key,
 * a signed prekey (the last two are kept) and one-time prekeys. Private parts never leave this object
 * except through writeTo(), whose output must be encrypted at rest (StateVault).
 */
public final class Identity {
    private final DeviceKeyPair sig;
    private final X25519Keys.Pair ik;
    private final byte[] sigIkDh;
    private final Map<Integer, X25519Keys.Pair> spks;
    private int currentSpkId;
    private byte[] currentSigSpk;
    private final Map<Integer, X25519Keys.Pair> opks;
    private int nextOpkId;

    public Identity(DeviceKeyPair sigKeyPair) {
        this.sig = sigKeyPair;
        this.ik = X25519Keys.generate();
        this.sigIkDh = DeviceAuth.signBytes(sig.priv(), PrekeyBundle.ikMessage(ik.pub()));
        this.spks = new LinkedHashMap<>();
        this.opks = new HashMap<>();
        this.nextOpkId = 1;
        rotateSignedPrekey();
    }

    private Identity(
            DeviceKeyPair sig,
            X25519Keys.Pair ik,
            byte[] sigIkDh,
            Map<Integer, X25519Keys.Pair> spks,
            int currentSpkId,
            byte[] currentSigSpk,
            Map<Integer, X25519Keys.Pair> opks,
            int nextOpkId) {
        this.sig = sig;
        this.ik = ik;
        this.sigIkDh = sigIkDh;
        this.spks = spks;
        this.currentSpkId = currentSpkId;
        this.currentSigSpk = currentSigSpk;
        this.opks = opks;
        this.nextOpkId = nextOpkId;
    }

    public static Identity generate() {
        return new Identity(DeviceAuth.generateKeyPair());
    }

    public synchronized void rotateSignedPrekey() {
        currentSpkId++;
        X25519Keys.Pair p = X25519Keys.generate();
        spks.put(currentSpkId, p);
        currentSigSpk = DeviceAuth.signBytes(sig.priv(), PrekeyBundle.spkMessage(currentSpkId, p.pub()));
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
        return sig.pub().clone();
    }

    public String uid() {
        return Ids.uid(sig.pub());
    }

    public DeviceKeyPair deviceKeys() {
        return sig;
    }

    // ---------------------------------------------------------------- persistence

    public synchronized void writeTo(BinWriter w) {
        w.bytes(sig.priv()).bytes(ik.priv()).bytes(sigIkDh);
        w.i32(currentSpkId).bytes(currentSigSpk);
        w.i32(spks.size());
        for (Map.Entry<Integer, X25519Keys.Pair> e : spks.entrySet()) {
            w.i32(e.getKey()).bytes(e.getValue().priv());
        }
        w.i32(nextOpkId).i32(opks.size());
        for (Map.Entry<Integer, X25519Keys.Pair> e : opks.entrySet()) {
            w.i32(e.getKey()).bytes(e.getValue().priv());
        }
    }

    public static Identity readFrom(BinReader r) {
        DeviceKeyPair sig = DeviceAuth.fromPrivate(r.bytes());
        X25519Keys.Pair ik = X25519Keys.fromPrivate(r.bytes());
        byte[] sigIkDh = r.bytes();
        int currentSpkId = r.i32();
        byte[] currentSigSpk = r.bytes();
        Map<Integer, X25519Keys.Pair> spks = new LinkedHashMap<>();
        int n = r.count();
        for (int i = 0; i < n; i++) {
            int id = r.i32();
            spks.put(id, X25519Keys.fromPrivate(r.bytes()));
        }
        int nextOpkId = r.i32();
        Map<Integer, X25519Keys.Pair> opks = new HashMap<>();
        int m = r.count();
        for (int i = 0; i < m; i++) {
            int id = r.i32();
            opks.put(id, X25519Keys.fromPrivate(r.bytes()));
        }
        return new Identity(sig, ik, sigIkDh, spks, currentSpkId, currentSigSpk, opks, nextOpkId);
    }
}

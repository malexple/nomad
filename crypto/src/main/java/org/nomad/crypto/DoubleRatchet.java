package org.nomad.crypto;

import java.security.GeneralSecurityException;
import java.security.MessageDigest;
import java.util.Arrays;
import java.util.LinkedHashMap;
import java.util.Map;
import javax.crypto.Cipher;
import javax.crypto.spec.GCMParameterSpec;
import javax.crypto.spec.SecretKeySpec;

/**
 * Double Ratchet (Signal specification). KDF_RK = HKDF(salt = rk, ikm = dh_out), KDF_CK = HMAC-SHA256
 * with constants 0x01 (message key) and 0x02 (next chain key). The message key is expanded into an
 * AES-256-GCM key and a nonce; each message key is used once, so a derived nonce is safe.
 * Header (in clear, authenticated as AAD): sender ratchet public key (32) | pn (4) | n (4).
 * decrypt() is atomic: on any failure the state stays unchanged.
 */
public final class DoubleRatchet {
    static final int MAX_SKIP = 1000;
    private static final int MAX_STORED_SKIPPED = 2000;
    private static final int HEADER_SIZE = 40;
    private static final int TAG_BYTES = 16;
    private static final byte[] INFO_RK = Bytes.ascii("nomad-dr-rk-v0");
    private static final byte[] INFO_MSG = Bytes.ascii("nomad-dr-msg-v0");

    private final byte[] ad;
    private X25519Keys.Pair dhs;
    private byte[] dhr;
    private byte[] rk;
    private byte[] cks;
    private byte[] ckr;
    private int ns;
    private int nr;
    private int pn;
    private final Map<String, byte[]> skipped = new LinkedHashMap<>();

    private DoubleRatchet(byte[] ad) {
        this.ad = ad;
    }

    static DoubleRatchet initAlice(byte[] sk, byte[] bobSpkPub, byte[] ad) {
        DoubleRatchet s = new DoubleRatchet(ad);
        s.dhs = X25519Keys.generate();
        s.dhr = bobSpkPub.clone();
        byte[][] r = kdfRk(sk, X25519Keys.dh(s.dhs.priv(), s.dhr));
        s.rk = r[0];
        s.cks = r[1];
        return s;
    }

    static DoubleRatchet initBob(byte[] sk, X25519Keys.Pair spk, byte[] ad) {
        DoubleRatchet s = new DoubleRatchet(ad);
        s.dhs = spk;
        s.rk = sk;
        return s;
    }

    public synchronized boolean canSend() {
        return cks != null;
    }

    public synchronized byte[] encrypt(byte[] plaintext) {
        if (cks == null) {
            throw new IllegalStateException("no sending chain yet: a message must be received first");
        }
        byte[][] r = kdfCk(cks);
        cks = r[0];
        byte[] header = Bytes.concat(dhs.pub(), Bytes.intBE(pn), Bytes.intBE(ns));
        ns++;
        return Bytes.concat(header, crypt(true, r[1], plaintext, Bytes.concat(ad, header)));
    }

    public synchronized byte[] decrypt(byte[] msg) {
        DoubleRatchet work = copy();
        byte[] plaintext;
        try {
            plaintext = work.decryptOn(msg);
        } catch (DecryptionException e) {
            throw e;
        } catch (RuntimeException e) {
            throw new DecryptionException("bad message: " + e.getMessage(), e);
        }
        adopt(work);
        return plaintext;
    }

    private byte[] decryptOn(byte[] msg) {
        if (msg.length < HEADER_SIZE + TAG_BYTES) {
            throw new DecryptionException("message too short");
        }
        byte[] header = Arrays.copyOfRange(msg, 0, HEADER_SIZE);
        byte[] body = Arrays.copyOfRange(msg, HEADER_SIZE, msg.length);
        byte[] hDh = Arrays.copyOfRange(header, 0, 32);
        int hPn = Bytes.readInt(header, 32);
        int hN = Bytes.readInt(header, 36);
        if (hPn < 0 || hN < 0) {
            throw new DecryptionException("bad counters");
        }
        byte[] aad = Bytes.concat(ad, header);

        byte[] stored = skipped.remove(skippedKey(hDh, hN));
        if (stored != null) {
            return crypt(false, stored, body, aad);
        }
        if (dhr == null || !MessageDigest.isEqual(hDh, dhr)) {
            skipMessageKeys(hPn);
            dhRatchet(hDh);
        }
        skipMessageKeys(hN);
        if (ckr == null) {
            throw new DecryptionException("no receiving chain");
        }
        byte[][] r = kdfCk(ckr);
        ckr = r[0];
        nr++;
        return crypt(false, r[1], body, aad);
    }

    private void skipMessageKeys(int until) {
        if (until > nr + MAX_SKIP) {
            throw new DecryptionException("too many skipped messages");
        }
        if (ckr == null) {
            return;
        }
        while (nr < until) {
            byte[][] r = kdfCk(ckr);
            ckr = r[0];
            skipped.put(skippedKey(dhr, nr), r[1]);
            nr++;
            while (skipped.size() > MAX_STORED_SKIPPED) {
                skipped.remove(skipped.keySet().iterator().next());
            }
        }
    }

    private void dhRatchet(byte[] newDhr) {
        pn = ns;
        ns = 0;
        nr = 0;
        dhr = newDhr;
        byte[][] r = kdfRk(rk, X25519Keys.dh(dhs.priv(), dhr));
        rk = r[0];
        ckr = r[1];
        dhs = X25519Keys.generate();
        r = kdfRk(rk, X25519Keys.dh(dhs.priv(), dhr));
        rk = r[0];
        cks = r[1];
    }

    private static byte[][] kdfRk(byte[] rk, byte[] dhOut) {
        byte[] okm = Hkdf.derive(rk, dhOut, INFO_RK, 64);
        return new byte[][] {Arrays.copyOfRange(okm, 0, 32), Arrays.copyOfRange(okm, 32, 64)};
    }

    /** Returns {next chain key, message key}. */
    private static byte[][] kdfCk(byte[] ck) {
        byte[] mk = Hkdf.hmac(ck, new byte[] {0x01});
        byte[] next = Hkdf.hmac(ck, new byte[] {0x02});
        return new byte[][] {next, mk};
    }

    private static byte[] crypt(boolean encrypt, byte[] mk, byte[] data, byte[] aad) {
        byte[] okm = Hkdf.derive(new byte[32], mk, INFO_MSG, 44);
        SecretKeySpec key = new SecretKeySpec(okm, 0, 32, "AES");
        byte[] nonce = Arrays.copyOfRange(okm, 32, 44);
        try {
            Cipher c = Cipher.getInstance("AES/GCM/NoPadding");
            c.init(encrypt ? Cipher.ENCRYPT_MODE : Cipher.DECRYPT_MODE, key, new GCMParameterSpec(128, nonce));
            c.updateAAD(aad);
            return c.doFinal(data);
        } catch (GeneralSecurityException e) {
            if (encrypt) {
                throw new IllegalStateException(e);
            }
            throw new DecryptionException("authentication failed", e);
        }
    }

    private static String skippedKey(byte[] dh, int n) {
        return Bytes.hex(dh) + ":" + n;
    }

    private DoubleRatchet copy() {
        DoubleRatchet c = new DoubleRatchet(ad);
        c.dhs = dhs;
        c.dhr = dhr;
        c.rk = rk;
        c.cks = cks;
        c.ckr = ckr;
        c.ns = ns;
        c.nr = nr;
        c.pn = pn;
        c.skipped.putAll(skipped);
        return c;
    }

    private void adopt(DoubleRatchet o) {
        dhs = o.dhs;
        dhr = o.dhr;
        rk = o.rk;
        cks = o.cks;
        ckr = o.ckr;
        ns = o.ns;
        nr = o.nr;
        pn = o.pn;
        skipped.clear();
        skipped.putAll(o.skipped);
    }
}

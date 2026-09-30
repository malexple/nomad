package org.nomad.core;

import static org.junit.jupiter.api.Assertions.*;

import java.util.HexFormat;
import org.junit.jupiter.api.Test;

class IdsTest {
    @Test
    void uidIs26Base32CharsAndDeterministic() {
        byte[] key = new byte[32];
        String uid = Ids.uid(key);
        assertEquals(26, uid.length());
        assertTrue(uid.matches("[a-z2-7]{26}"));
        assertEquals(uid, Ids.uid(key));
    }

    @Test
    void mailboxIdIsDomainSeparatedHex() {
        byte[] key = new byte[32];
        String id = Ids.mailboxIdFor(key);
        assertTrue(id.matches("[0-9a-f]{64}"));
        assertNotEquals(HexFormat.of().formatHex(Ids.sha256(key)), id);
    }

    @Test
    void base32KnownVector() {
        // RFC 4648: "f" -> "MY", lower-cased and unpadded
        assertEquals("my", Ids.base32(new byte[] {'f'}));
        assertEquals("mzxw6", Ids.base32("foo".getBytes()));
    }
}

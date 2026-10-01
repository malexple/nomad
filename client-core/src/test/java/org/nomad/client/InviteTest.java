package org.nomad.client;

import static org.junit.jupiter.api.Assertions.*;

import org.junit.jupiter.api.Test;
import org.nomad.core.DeviceAuth;
import org.nomad.core.DeviceKeyPair;

class InviteTest {
    @Test
    void roundTripWithRussianNameAndServer() {
        DeviceKeyPair kp = DeviceAuth.generateKeyPair();
        String link = Invite.create(kp.pub(), "Мама", "ws://192.168.88.210:8090/v1/ws");
        assertTrue(link.startsWith("nomad://invite?k="));
        assertFalse(link.contains(" "));

        Invite.Data d = Invite.parse(link);
        assertArrayEquals(kp.pub(), d.sigKey());
        assertEquals("Мама", d.name());
        assertEquals("ws://192.168.88.210:8090/v1/ws", d.serverUrl());
        assertEquals(kp.uid(), d.uid());
    }

    @Test
    void theLinkIsFoundInsidePastedText() {
        DeviceKeyPair kp = DeviceAuth.generateKeyPair();
        String link = Invite.create(kp.pub(), "Папа", "ws://x/v1/ws");
        Invite.Data d = Invite.parse("Привет! Вот моё приглашение:\n" + link + "\nДобавь меня.");
        assertEquals(kp.uid(), d.uid());
        assertEquals("Папа", d.name());
    }

    @Test
    void garbageIsRejected() {
        assertThrows(IllegalArgumentException.class, () -> Invite.parse("hello"));
        assertThrows(IllegalArgumentException.class, () -> Invite.parse("nomad://invite?n=AAAA"));
        assertThrows(IllegalArgumentException.class, () -> Invite.parse("nomad://invite?k=AAAA"));
        assertThrows(IllegalArgumentException.class, () -> Invite.parse("nomad://invite?k=%%%"));
    }
}

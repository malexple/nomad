package org.nomad.client;

import static org.junit.jupiter.api.Assertions.*;

import java.util.List;
import java.util.concurrent.atomic.AtomicInteger;
import org.junit.jupiter.api.Test;
import org.nomad.core.DeviceAuth;
import org.nomad.core.DeviceKeyPair;
import org.nomad.crypto.DecryptionException;
import org.nomad.crypto.StateVault;

class ChatStoreTest {
    private static ChatStore store() {
        return ChatStore.create("ws://node/v1/ws");
    }

    @Test
    void aConfirmedNameIsNeverReplacedByAnUnconfirmedOne() {
        ChatStore s = store();
        DeviceKeyPair kp = DeviceAuth.generateKeyPair();

        s.addOrUpdateContact(kp.pub(), "", false);
        assertEquals("", s.contact(kp.uid()).name());
        assertFalse(s.contact(kp.uid()).confirmed());

        s.addOrUpdateContact(kp.pub(), "Мама", true);
        assertEquals("Мама", s.contact(kp.uid()).name());
        assertTrue(s.contact(kp.uid()).confirmed());

        s.addOrUpdateContact(kp.pub(), "", false);
        s.addOrUpdateContact(kp.pub(), "Кто-то", false);
        assertEquals("Мама", s.contact(kp.uid()).name());

        s.addOrUpdateContact(kp.pub(), "", true);
        assertEquals("Мама", s.contact(kp.uid()).name());
        assertEquals(1, s.contacts().size());
    }

    @Test
    void messagesUnreadCountsAndTheOpenChat() {
        ChatStore s = store();
        s.addMessage("u:a", false, "a", "раз", 100, ChatStore.STATUS_OK);
        s.addMessage("u:a", false, "a", "два", 200, ChatStore.STATUS_OK);
        s.addMessage("u:b", false, "b", "привет", 300, ChatStore.STATUS_OK);
        assertEquals(2, s.chats().stream().filter(c -> c.chatId().equals("u:a")).findFirst().orElseThrow().unread());

        assertEquals("u:b", s.chats().get(0).chatId(), "the newest chat is first");

        s.setOpenChat("u:a");
        assertEquals(0, s.chats().stream().filter(c -> c.chatId().equals("u:a")).findFirst().orElseThrow().unread());
        s.addMessage("u:a", false, "a", "три", 400, ChatStore.STATUS_OK);
        assertEquals(0, s.chats().stream().filter(c -> c.chatId().equals("u:a")).findFirst().orElseThrow().unread());

        s.setOpenChat(null);
        s.addMessage("u:a", false, "a", "четыре", 500, ChatStore.STATUS_OK);
        assertEquals(1, s.chats().get(0).unread());
        assertEquals("четыре", s.chats().get(0).lastText());
    }

    @Test
    void outgoingMessageStatusIsUpdated() {
        ChatStore s = store();
        long id = s.addMessage("u:a", true, "me", "исходящее", 1, ChatStore.STATUS_SENDING);
        assertEquals(ChatStore.STATUS_SENDING, s.messages("u:a").get(0).status());
        s.setStatus(id, ChatStore.STATUS_OK);
        assertEquals(ChatStore.STATUS_OK, s.messages("u:a").get(0).status());
        s.setStatus(id, ChatStore.STATUS_FAILED);
        assertEquals(ChatStore.STATUS_FAILED, s.messages("u:a").get(0).status());
        assertEquals(0, s.chats().get(0).unread(), "own messages are never unread");
    }

    @Test
    void theListenerIsCalledOnChanges() {
        ChatStore s = store();
        AtomicInteger calls = new AtomicInteger();
        s.setListener(calls::incrementAndGet);
        s.setMyName("Я");
        s.addMessage("u:a", true, "me", "x", 1, ChatStore.STATUS_OK);
        s.ensureChat("g:1");
        s.ensureChat("g:1");
        assertEquals(3, calls.get(), "ensureChat of an existing chat changes nothing");
    }

    @Test
    void sealAndOpenKeepEverything() {
        ChatStore s = store();
        DeviceKeyPair kp = DeviceAuth.generateKeyPair();
        s.setMyName("Папа");
        s.setNodeUrl("ws://192.168.88.210:8090/v1/ws");
        s.addOrUpdateContact(kp.pub(), "Мама", true);
        s.setGroupName("abc", "Семья");
        s.addMessage("u:" + kp.uid(), false, kp.uid(), "привет", 10, ChatStore.STATUS_OK);
        s.addMessage("g:abc", true, "me", "всем", 20, ChatStore.STATUS_FAILED);

        byte[] key = StateVault.newKey();
        ChatStore back = ChatStore.open(key, s.seal(key));
        assertEquals("Папа", back.myName());
        assertEquals("ws://192.168.88.210:8090/v1/ws", back.nodeUrl());
        assertEquals("Мама", back.contact(kp.uid()).name());
        assertArrayEquals(kp.pub(), back.contact(kp.uid()).sigKey());
        assertEquals("Семья", back.groupName("abc"));
        assertEquals("привет", back.messages("u:" + kp.uid()).get(0).text());
        assertEquals(ChatStore.STATUS_FAILED, back.messages("g:abc").get(0).status());
        assertEquals(1, back.chats().stream().filter(c -> c.chatId().equals("u:" + kp.uid())).findFirst().orElseThrow().unread());

        long nextId = back.addMessage("g:abc", true, "me", "ещё", 30, ChatStore.STATUS_OK);
        assertTrue(nextId > 2, "ids continue after a reload");

        byte[] wrong = StateVault.newKey();
        assertThrows(DecryptionException.class, () -> ChatStore.open(wrong, s.seal(key)));
    }

    @Test
    void contactsAreSortedByName() {
        ChatStore s = store();
        s.addOrUpdateContact(DeviceAuth.generateKeyPair().pub(), "Яна", true);
        s.addOrUpdateContact(DeviceAuth.generateKeyPair().pub(), "Аня", true);
        List<ChatStore.Contact> list = s.contacts();
        assertEquals("Аня", list.get(0).name());
        assertEquals("Яна", list.get(1).name());
    }
}

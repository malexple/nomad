package org.nomad.client;

import java.util.ArrayList;
import java.util.Arrays;
import java.util.Comparator;
import java.util.HashMap;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import org.nomad.core.BinReader;
import org.nomad.core.BinWriter;
import org.nomad.core.Ids;
import org.nomad.crypto.StateVault;

/**
 * What the user sees: contacts, chats with their messages, group names, the user's own name and the node address.
 * Chat ids: "u:UID" for a direct chat, "g:GROUPID" for a group. Kept apart from ClientState (the cryptographic state);
 * it is sealed with the same master key (seal/open). All methods are thread-safe; the change listener is called outside
 * the lock, on whichever thread made the change.
 */
public final class ChatStore {
    public static final int STATUS_SENDING = 0;
    public static final int STATUS_OK = 1;
    public static final int STATUS_FAILED = 2;

    private static final byte[] MAGIC = {'N', 'M', 'A', '1'};
    private static final int FORMAT = 1;
    private static final int MAX_PER_CHAT = 2000;

    /** confirmed = the name was typed by the user or came from an invitation; unconfirmed = learned from a message. */
    public record Contact(String uid, byte[] sigKey, String name, boolean confirmed) {}

    public record Message(
            long id, String chatId, boolean outgoing, String senderUid, String text, long time, int status) {}

    public record ChatSummary(String chatId, String lastText, long lastTime, int unread) {}

    private final Map<String, Contact> contacts = new LinkedHashMap<>();
    private final Map<String, List<Message>> messages = new LinkedHashMap<>();
    private final Map<String, Integer> unread = new HashMap<>();
    private final Map<String, String> groupNames = new HashMap<>();
    private String myName = "";
    private String nodeUrl;
    private long nextId = 1;
    private String openChat;
    private Runnable listener;

    private ChatStore(String nodeUrl) {
        this.nodeUrl = nodeUrl;
    }

    public static ChatStore create(String nodeUrl) {
        return new ChatStore(nodeUrl);
    }

    public synchronized void setListener(Runnable listener) {
        this.listener = listener;
    }

    private void fire() {
        Runnable l;
        synchronized (this) {
            l = listener;
        }
        if (l != null) {
            l.run();
        }
    }

    // ---------------------------------------------------------------- settings

    public synchronized String myName() {
        return myName;
    }

    public void setMyName(String name) {
        synchronized (this) {
            myName = name.trim();
        }
        fire();
    }

    public synchronized String nodeUrl() {
        return nodeUrl;
    }

    public void setNodeUrl(String url) {
        synchronized (this) {
            nodeUrl = url.trim();
        }
        fire();
    }

    // ---------------------------------------------------------------- contacts

    /**
     * Adds a contact or updates it. A confirmed name is never replaced by an unconfirmed one; an empty name keeps the old one.
     */
    public Contact addOrUpdateContact(byte[] sigKey, String name, boolean confirmed) {
        Contact result;
        boolean changed;
        synchronized (this) {
            String uid = Ids.uid(sigKey);
            Contact old = contacts.get(uid);
            String cleaned = name == null ? "" : name.trim();
            if (old == null) {
                result = new Contact(uid, sigKey.clone(), cleaned, confirmed);
                changed = true;
            } else if (confirmed && (!cleaned.isEmpty() || !old.confirmed())) {
                result = new Contact(uid, old.sigKey(), cleaned.isEmpty() ? old.name() : cleaned, true);
                changed = !result.equals(old) && (!result.name().equals(old.name()) || !old.confirmed());
            } else if (!old.confirmed() && !cleaned.isEmpty() && old.name().isEmpty()) {
                result = new Contact(uid, old.sigKey(), cleaned, false);
                changed = true;
            } else {
                result = old;
                changed = false;
            }
            if (changed) {
                contacts.put(uid, result);
            }
        }
        if (changed) {
            fire();
        }
        return result;
    }

    public synchronized Contact contact(String uid) {
        return contacts.get(uid);
    }

    public synchronized List<Contact> contacts() {
        List<Contact> out = new ArrayList<>(contacts.values());
        out.sort(Comparator.comparing((Contact c) -> c.name().toLowerCase()).thenComparing(Contact::uid));
        return out;
    }

    // ---------------------------------------------------------------- groups

    public synchronized String groupName(String groupId) {
        return groupNames.getOrDefault(groupId, "");
    }

    public void setGroupName(String groupId, String name) {
        synchronized (this) {
            groupNames.put(groupId, name.trim());
        }
        fire();
    }

    // ---------------------------------------------------------------- chats and messages

    /** Makes the chat appear in the list even if it has no messages yet. */
    public void ensureChat(String chatId) {
        boolean created;
        synchronized (this) {
            created = !messages.containsKey(chatId);
            if (created) {
                messages.put(chatId, new ArrayList<>());
            }
        }
        if (created) {
            fire();
        }
    }

    /** @return the id of the new message */
    public long addMessage(
            String chatId, boolean outgoing, String senderUid, String text, long time, int status) {
        long id;
        synchronized (this) {
            id = nextId++;
            List<Message> list = messages.computeIfAbsent(chatId, k -> new ArrayList<>());
            list.add(new Message(id, chatId, outgoing, senderUid, text, time, status));
            while (list.size() > MAX_PER_CHAT) {
                list.remove(0);
            }
            if (!outgoing && !chatId.equals(openChat)) {
                unread.merge(chatId, 1, Integer::sum);
            }
        }
        fire();
        return id;
    }

    public void setStatus(long messageId, int status) {
        boolean changed = false;
        synchronized (this) {
            for (List<Message> list : messages.values()) {
                for (int i = list.size() - 1; i >= 0; i--) {
                    Message m = list.get(i);
                    if (m.id() == messageId) {
                        if (m.status() != status) {
                            list.set(i, new Message(
                                    m.id(), m.chatId(), m.outgoing(), m.senderUid(), m.text(), m.time(), status));
                            changed = true;
                        }
                        break;
                    }
                }
            }
        }
        if (changed) {
            fire();
        }
    }

    public synchronized List<Message> messages(String chatId) {
        List<Message> list = messages.get(chatId);
        return list == null ? List.of() : new ArrayList<>(list);
    }

    /** The chat the user is looking at: its incoming messages are not counted as unread. */
    public void setOpenChat(String chatId) {
        synchronized (this) {
            openChat = chatId;
            if (chatId != null) {
                unread.remove(chatId);
            }
        }
        fire();
    }

    public synchronized List<ChatSummary> chats() {
        List<ChatSummary> out = new ArrayList<>();
        for (Map.Entry<String, List<Message>> e : messages.entrySet()) {
            List<Message> list = e.getValue();
            Message last = list.isEmpty() ? null : list.get(list.size() - 1);
            out.add(new ChatSummary(
                    e.getKey(),
                    last == null ? "" : last.text(),
                    last == null ? 0 : last.time(),
                    unread.getOrDefault(e.getKey(), 0)));
        }
        out.sort(Comparator.comparingLong(ChatSummary::lastTime).reversed().thenComparing(ChatSummary::chatId));
        return out;
    }

    // ---------------------------------------------------------------- persistence

    public byte[] seal(byte[] masterKey) {
        BinWriter w = new BinWriter();
        synchronized (this) {
            w.bytes(MAGIC).i32(FORMAT);
            w.str(myName).str(nodeUrl).i64(nextId);
            w.i32(contacts.size());
            for (Contact c : contacts.values()) {
                w.bytes(c.sigKey()).str(c.name()).bool(c.confirmed());
            }
            w.i32(groupNames.size());
            for (Map.Entry<String, String> e : groupNames.entrySet()) {
                w.str(e.getKey()).str(e.getValue());
            }
            w.i32(messages.size());
            for (Map.Entry<String, List<Message>> e : messages.entrySet()) {
                w.str(e.getKey()).i32(unread.getOrDefault(e.getKey(), 0)).i32(e.getValue().size());
                for (Message m : e.getValue()) {
                    w.i64(m.id()).bool(m.outgoing()).str(m.senderUid()).str(m.text()).i64(m.time()).i32(m.status());
                }
            }
        }
        return StateVault.seal(masterKey, w.toByteArray());
    }

    public static ChatStore open(byte[] masterKey, byte[] sealed) {
        BinReader r = new BinReader(StateVault.open(masterKey, sealed));
        if (!Arrays.equals(r.bytes(), MAGIC) || r.i32() != FORMAT) {
            throw new IllegalArgumentException("unknown chat file format");
        }
        String name = r.str();
        ChatStore s = new ChatStore(r.str());
        s.myName = name;
        s.nextId = r.i64();
        int contactCount = r.count();
        for (int i = 0; i < contactCount; i++) {
            byte[] key = r.bytes();
            String contactName = r.str();
            Contact c = new Contact(Ids.uid(key), key, contactName, r.bool());
            s.contacts.put(c.uid(), c);
        }
        int groupCount = r.count();
        for (int i = 0; i < groupCount; i++) {
            String id = r.str();
            s.groupNames.put(id, r.str());
        }
        int chatCount = r.count();
        for (int i = 0; i < chatCount; i++) {
            String chatId = r.str();
            int unreadCount = r.i32();
            if (unreadCount > 0) {
                s.unread.put(chatId, unreadCount);
            }
            int n = r.count();
            List<Message> list = new ArrayList<>();
            for (int j = 0; j < n; j++) {
                long id = r.i64();
                boolean outgoing = r.bool();
                String sender = r.str();
                String text = r.str();
                long time = r.i64();
                list.add(new Message(id, chatId, outgoing, sender, text, time, r.i32()));
            }
            s.messages.put(chatId, list);
        }
        return s;
    }
}

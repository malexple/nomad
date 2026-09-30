package org.nomad.client;

import java.io.IOException;
import java.util.Arrays;
import org.nomad.core.BinReader;
import org.nomad.core.BinWriter;
import org.nomad.core.DeviceKeyPair;
import org.nomad.crypto.ConversationManager;
import org.nomad.crypto.GroupManager;
import org.nomad.crypto.Identity;
import org.nomad.crypto.StateVault;

/**
 * Everything a device must not lose: identity and prekeys, pairwise sessions, group chains, the read cursor and the
 * held messages. seal() returns one blob encrypted with the 32-byte master key (StateVault).
 * The components are written one after another; call seal() when no message is being processed.
 */
public final class ClientState {
    private static final byte[] MAGIC = {'N', 'M', 'D', '1'};
    private static final int FORMAT = 1;

    public final Identity identity;
    public final ConversationManager conversations;
    public final GroupManager groups;
    public final WsInbox inbox;

    public ClientState(Identity identity, ConversationManager conversations, GroupManager groups, WsInbox inbox) {
        this.identity = identity;
        this.conversations = conversations;
        this.groups = groups;
        this.inbox = inbox;
    }

    public static ClientState fresh(DeviceKeyPair keys) {
        Identity id = new Identity(keys);
        return new ClientState(id, new ConversationManager(id), new GroupManager(id), new WsInbox(new CursorState()));
    }

    public byte[] seal(byte[] masterKey) {
        BinWriter w = new BinWriter();
        w.bytes(MAGIC).i32(FORMAT);
        identity.writeTo(w);
        conversations.writeTo(w);
        groups.writeTo(w);
        inbox.writeTo(w);
        return StateVault.seal(masterKey, w.toByteArray());
    }

    /** @throws org.nomad.crypto.DecryptionException wrong key or damaged data, IllegalArgumentException for a bad format */
    public static ClientState open(byte[] masterKey, byte[] sealed) {
        BinReader r = new BinReader(StateVault.open(masterKey, sealed));
        if (!Arrays.equals(r.bytes(), MAGIC) || r.i32() != FORMAT) {
            throw new IllegalArgumentException("unknown state format");
        }
        Identity id = Identity.readFrom(r);
        ConversationManager convo = ConversationManager.readFrom(id, r);
        GroupManager groups = GroupManager.readFrom(id, r);
        WsInbox inbox = WsInbox.readFrom(r);
        return new ClientState(id, convo, groups, inbox);
    }

    public void save(StateStore store, byte[] masterKey) throws IOException {
        store.save(seal(masterKey));
    }

    /** @return null if the store is empty */
    public static ClientState load(StateStore store, byte[] masterKey) throws IOException {
        byte[] sealed = store.load();
        return sealed == null ? null : open(masterKey, sealed);
    }
}

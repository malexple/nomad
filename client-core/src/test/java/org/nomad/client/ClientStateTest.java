package org.nomad.client;

import static org.junit.jupiter.api.Assertions.*;

import java.nio.file.Path;
import java.util.List;
import java.util.UUID;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;
import org.nomad.core.DeviceAuth;
import org.nomad.crypto.DecryptionException;
import org.nomad.crypto.StateVault;

class ClientStateTest {
    @Test
    void cursorAndHeldMessagesSurviveSealAndOpen() {
        byte[] key = StateVault.newKey();
        ClientState s = ClientState.fresh(DeviceAuth.generateKeyPair());
        Received kept = new Received(5, UUID.randomUUID(), 1, new byte[] {7, 8, 9});
        s.inbox.onMsgs(new WsProtocol.Msgs(3, false, List.of(kept)));
        s.inbox.hold(kept);

        ClientState back = ClientState.open(key, s.seal(key));
        assertEquals(s.identity.uid(), back.identity.uid());
        assertEquals(5, back.inbox.afterSeq());
        assertEquals(1, back.inbox.heldCount());
        assertArrayEquals(new byte[] {7, 8, 9}, back.inbox.heldItems().get(0).payload());
        assertEquals(4, back.inbox.safeAckSeq());

        // the restored inbox still knows that the envelope was already seen
        var again = back.inbox.onMsgs(new WsProtocol.Msgs(3, false, List.of(kept)));
        assertTrue(again.fresh().isEmpty());
    }

    @Test
    void wrongKeyIsRejected() {
        ClientState s = ClientState.fresh(DeviceAuth.generateKeyPair());
        byte[] sealed = s.seal(StateVault.newKey());
        byte[] otherKey = StateVault.newKey();
        assertThrows(DecryptionException.class, () -> ClientState.open(otherKey, sealed));
    }

    @Test
    void fileStoreRoundTripAndEmptyStore(@TempDir Path dir) throws Exception {
        FileStateStore store = new FileStateStore(dir.resolve("state.bin"));
        byte[] key = StateVault.newKey();
        assertNull(ClientState.load(store, key));

        ClientState s = ClientState.fresh(DeviceAuth.generateKeyPair());
        s.save(store, key);
        assertEquals(s.identity.uid(), ClientState.load(store, key).identity.uid());

        ClientState s2 = ClientState.fresh(DeviceAuth.generateKeyPair());
        s2.save(store, key);
        assertEquals(s2.identity.uid(), ClientState.load(store, key).identity.uid());
    }
}

package org.nomad.app

import android.content.Context
import java.io.File
import org.nomad.client.ClientState
import org.nomad.client.FileStateStore
import org.nomad.client.StateStore
import org.nomad.core.DeviceAuth
import org.nomad.crypto.ConversationManager

/**
 * The client state on disk. The two functions below are the only way the app should use the sessions, because they
 * keep the rule of docs/state.md: the state is saved BEFORE a ciphertext is handed out for sending and after every
 * message that changed a session.
 */
class StateRepository private constructor(
    private val store: StateStore,
    private val masterKey: ByteArray,
    val state: ClientState,
    val loadedFromDisk: Boolean,
) {
    @Synchronized
    fun save() {
        state.save(store, masterKey)
    }

    /** Encrypts and saves; only after this returns may the ciphertext be sent. */
    @Synchronized
    fun encryptFor(peerUid: String, plaintext: ByteArray): ByteArray {
        val wire = state.conversations.encryptFor(peerUid, plaintext)
        save()
        return wire
    }

    /** Decrypts and saves; only after this returns may the message be acknowledged to the node. */
    @Synchronized
    fun decrypt(wire: ByteArray): ConversationManager.Decrypted? {
        val result = state.conversations.decrypt(wire)
        if (result.isPresent) {
            save()
        }
        return result.orElse(null)
    }

    companion object {
        /**
         * Opens the saved state, or creates a new identity if nothing has been saved yet.
         * A damaged file or a wrong key throws: it is never replaced silently.
         */
        fun open(store: StateStore, masterKey: ByteArray): StateRepository {
            val loaded = ClientState.load(store, masterKey)
            val state = loaded ?: ClientState.fresh(DeviceAuth.generateKeyPair()).also { it.save(store, masterKey) }
            return StateRepository(store, masterKey, state, loaded != null)
        }

        fun forContext(context: Context): StateRepository {
            val key = KeystoreMasterKey.getOrCreate(context)
            return open(FileStateStore(File(context.filesDir, "state.bin").toPath()), key)
        }
    }
}

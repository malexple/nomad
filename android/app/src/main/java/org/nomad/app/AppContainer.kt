package org.nomad.app

import android.content.Context
import android.os.Handler
import android.os.Looper
import androidx.compose.runtime.mutableIntStateOf
import androidx.compose.runtime.mutableStateOf
import java.io.File
import java.util.concurrent.Executors
import java.util.concurrent.TimeUnit
import org.nomad.client.ChatStore
import org.nomad.client.FileStateStore
import org.nomad.client.Invite
import org.nomad.client.NomadEngine
import org.nomad.core.Ids
import org.nomad.crypto.GroupManager

/** The address of the development node on the home network; changeable on the profile screen. */
const val DEFAULT_NODE_URL = "ws://192.168.88.210:8090/v1/ws"

/**
 * Everything the screens need, created once per process: the cryptographic state (StateRepository), the visible data
 * (ChatStore, saved sealed in chats.bin with the same master key) and the engine.
 * Compose reads [version], [connection] and [notice]; they change on the main thread.
 */
class AppContainer(context: Context) {
    private val masterKey: ByteArray = KeystoreMasterKey.getOrCreate(context)
    val repository: StateRepository =
        StateRepository.open(FileStateStore(File(context.filesDir, "state.bin").toPath()), masterKey)
    private val chatFile = FileStateStore(File(context.filesDir, "chats.bin").toPath())
    val chats: ChatStore = loadChats()

    val version = mutableIntStateOf(0)
    val connection = mutableStateOf(NomadEngine.ConnectionState.OFFLINE)
    val notice = mutableStateOf<String?>(null)

    private val main = Handler(Looper.getMainLooper())
    private val saver = Executors.newSingleThreadScheduledExecutor()
    private val saveLock = Any()
    private var saveScheduled = false

    val myUid: String get() = repository.state.identity.uid()
    val mySigKey: ByteArray get() = repository.state.identity.sigPub()

    private val listener = object : NomadEngine.Listener {
        override fun onConnectionState(state: NomadEngine.ConnectionState) {
            main.post { connection.value = state }
        }

        override fun onChat(peerUid: String, peerSigKey: ByteArray, text: ByteArray) {
            chats.addOrUpdateContact(peerSigKey, "", false)
            chats.addMessage(
                "u:$peerUid", false, peerUid, String(text, Charsets.UTF_8), System.currentTimeMillis(),
                ChatStore.STATUS_OK,
            )
        }

        override fun onGroupMessage(groupId: String, senderUid: String, text: ByteArray) {
            chats.addMessage(
                "g:$groupId", false, senderUid, String(text, Charsets.UTF_8), System.currentTimeMillis(),
                ChatStore.STATUS_OK,
            )
        }

        override fun onGroupsChanged() {
            for (group in engine.groups()) {
                chats.ensureChat("g:" + group.groupId())
            }
            main.post { version.intValue++ }
        }

        override fun onSendFailed(reason: String) {
            main.post { notice.value = reason }
        }

        override fun onError(message: String) {
            main.post { notice.value = message }
        }
    }

    @Volatile
    var engine: NomadEngine = createEngine()
        private set

    init {
        chats.setListener { onChatsChanged() }
        for (group in engine.groups()) {
            chats.ensureChat("g:" + group.groupId())
        }
    }

    private fun loadChats(): ChatStore {
        val sealed = chatFile.load()
        return if (sealed == null) ChatStore.create(DEFAULT_NODE_URL) else ChatStore.open(masterKey, sealed)
    }

    private fun createEngine(): NomadEngine = NomadEngine(
        repository.state,
        repository.persistence,
        OkHttpTransport(),
        chats.nodeUrl(),
        listener,
        NomadEngine.Config.defaults(),
    )

    private fun onChatsChanged() {
        main.post { version.intValue++ }
        synchronized(saveLock) {
            if (saveScheduled) return
            saveScheduled = true
        }
        saver.schedule({
            synchronized(saveLock) { saveScheduled = false }
            try {
                chatFile.save(chats.seal(masterKey))
            } catch (e: Exception) {
                main.post { notice.value = "Не удалось сохранить переписку: $e" }
            }
        }, 300, TimeUnit.MILLISECONDS)
    }

    // ------------------------------------------------------------------ lifecycle

    fun start() = engine.start()

    fun stop() = engine.stop()

    /** Switches to another node: the engine is recreated, the keys and chats stay. */
    fun changeNodeUrl(url: String) {
        chats.setNodeUrl(url)
        val old = engine
        old.shutdown().whenComplete { _, _ ->
            engine = createEngine()
            engine.start()
        }
    }

    // ------------------------------------------------------------------ names

    fun nameOf(uid: String): String {
        if (uid == myUid) return "Я"
        val contact = chats.contact(uid)
        return if (contact == null || contact.name().isBlank()) "Контакт ${uid.take(4)}" else contact.name()
    }

    /**
     * A group is called by the name its admin chose (it travels inside the group state to every member).
     * Without a name: the local label, then the list of the members.
     */
    fun chatTitle(chatId: String): String {
        if (chatId.startsWith("u:")) return nameOf(chatId.removePrefix("u:"))
        val groupId = chatId.removePrefix("g:")
        val info = engine.groups().firstOrNull { it.groupId() == groupId }
        val shared = info?.name().orEmpty()
        if (shared.isNotBlank()) return shared
        val local = chats.groupName(groupId)
        if (local.isNotBlank()) return local
        val members = info?.members()?.map { nameOf(Ids.uid(it.sigKey())) } ?: emptyList()
        return if (members.isEmpty()) "Группа" else "Группа: " + members.joinToString(", ")
    }

    // ------------------------------------------------------------------ actions

    fun sendDirect(contact: ChatStore.Contact, text: String) {
        val id = chats.addMessage(
            "u:" + contact.uid(), true, myUid, text, System.currentTimeMillis(), ChatStore.STATUS_SENDING,
        )
        engine.sendChat(contact.sigKey(), text.toByteArray(Charsets.UTF_8)).whenComplete { _, error ->
            chats.setStatus(id, if (error == null) ChatStore.STATUS_OK else ChatStore.STATUS_FAILED)
        }
    }

    fun sendToGroup(groupId: String, text: String) {
        val id = chats.addMessage(
            "g:$groupId", true, myUid, text, System.currentTimeMillis(), ChatStore.STATUS_SENDING,
        )
        engine.sendGroup(groupId, text.toByteArray(Charsets.UTF_8)).whenComplete { _, error ->
            chats.setStatus(id, if (error == null) ChatStore.STATUS_OK else ChatStore.STATUS_FAILED)
        }
    }

    /** @return the chat id, or throws IllegalArgumentException with a message for the user */
    fun addContactFromInvite(text: String, nameOverride: String): String {
        val invite = Invite.parse(text)
        if (invite.uid() == myUid) throw IllegalArgumentException("это ваше собственное приглашение")
        val name = nameOverride.ifBlank { invite.name() }
        val contact = chats.addOrUpdateContact(invite.sigKey(), name, true)
        chats.ensureChat("u:" + contact.uid())
        return "u:" + contact.uid()
    }

    fun createGroup(name: String, members: List<ChatStore.Contact>, onDone: (String?, String?) -> Unit) {
        val list = members.map { GroupManager.GroupMember(it.sigKey()) }
        engine.createGroup(name, list).whenComplete { groupId, error ->
            if (error != null || groupId == null) {
                main.post { onDone(null, (error ?: IllegalStateException("не удалось создать группу")).toString()) }
            } else {
                chats.ensureChat("g:$groupId")
                main.post { onDone("g:$groupId", null) }
            }
        }
    }

    fun inviteLink(): String = Invite.create(mySigKey, chats.myName().ifBlank { "Без имени" }, chats.nodeUrl())
}

package org.nomad.app

import android.content.Context
import java.io.File
import java.util.Optional
import org.nomad.client.FileStateStore
import org.nomad.core.DeviceAuth
import org.nomad.crypto.ConversationManager
import org.nomad.crypto.GroupManager
import org.nomad.crypto.Hkdf
import org.nomad.crypto.StateVault
import org.nomad.crypto.X25519Keys

/**
 * Runs the protocol's crypto on the real device: signatures, key agreement, derivation, sealed state, a pairwise
 * conversation and a group chat, each surviving a simulated process kill (everything is re-read from disk).
 * Used by the "Run self-test" button and by the instrumented test.
 */
object SelfTest {
    data class Step(val name: String, val ok: Boolean, val detail: String)

    private fun utf8(s: String): ByteArray = s.toByteArray(Charsets.UTF_8)

    private fun text(d: ConversationManager.Decrypted?): String = String(d!!.plaintext(), Charsets.UTF_8)

    private fun groupText(o: Optional<GroupManager.GroupDecrypted>): String =
        String(o.get().plaintext(), Charsets.UTF_8)

    fun runAll(workDir: File): List<Step> {
        workDir.mkdirs()
        val steps = mutableListOf<Step>()

        fun step(name: String, block: () -> String) {
            val started = System.nanoTime()
            try {
                val detail = block()
                steps += Step(name, true, "$detail (${(System.nanoTime() - started) / 1_000_000} ms)")
            } catch (e: Throwable) {
                steps += Step(name, false, e.toString())
            }
        }

        step("Ed25519 signatures") {
            val keys = DeviceAuth.generateKeyPair()
            val data = utf8("nomad")
            val signature = DeviceAuth.signBytes(keys.priv(), data)
            check(signature.size == 64) { "signature length ${signature.size}" }
            check(DeviceAuth.verifyBytes(keys.pub(), data, signature)) { "a valid signature was rejected" }
            check(!DeviceAuth.verifyBytes(keys.pub(), utf8("other"), signature)) { "forged data was accepted" }
            "sign, verify, reject forged data"
        }

        step("X25519, HKDF, AES-GCM") {
            val a = X25519Keys.generate()
            val b = X25519Keys.generate()
            check(X25519Keys.dh(a.priv(), b.pub()).contentEquals(X25519Keys.dh(b.priv(), a.pub()))) {
                "the two sides computed different shared secrets"
            }
            check(Hkdf.derive(ByteArray(32), ByteArray(22) { 0x0b }, utf8("info"), 42).size == 42)
            val key = StateVault.newKey()
            check(StateVault.open(key, StateVault.seal(key, utf8("state"))).contentEquals(utf8("state")))
            "key agreement, key derivation, authenticated encryption"
        }

        step("Pairwise chat survives a process kill") {
            val key = StateVault.newKey()
            val aliceStore = FileStateStore(File(workDir, "alice.bin").toPath())
            val bobStore = FileStateStore(File(workDir, "bob.bin").toPath())
            var alice = StateRepository.open(aliceStore, key)
            var bob = StateRepository.open(bobStore, key)
            val aliceUid = alice.state.identity.uid()
            val bobUid = bob.state.identity.uid()

            val bundle = bob.state.identity.publicBundle().withOpk(bob.state.identity.generateOneTimePrekeys(1)[0])
            bob.save()
            alice.state.conversations.startSession(bobUid, bundle)

            check(text(bob.decrypt(alice.encryptFor(bobUid, utf8("hello")))) == "hello")
            check(text(alice.decrypt(bob.encryptFor(aliceUid, utf8("hi")))) == "hi")

            val third = alice.encryptFor(bobUid, utf8("three"))
            val fourth = alice.encryptFor(bobUid, utf8("four"))
            check(text(bob.decrypt(fourth)) == "four")

            alice = StateRepository.open(aliceStore, key)
            bob = StateRepository.open(bobStore, key)
            check(alice.loadedFromDisk && bob.loadedFromDisk) { "the state was not read from disk" }
            check(alice.state.identity.uid() == aliceUid && bob.state.identity.uid() == bobUid) { "identity changed" }

            check(text(bob.decrypt(third)) == "three") { "a skipped message key was lost" }
            check(text(bob.decrypt(alice.encryptFor(bobUid, utf8("five")))) == "five")
            check(text(alice.decrypt(bob.encryptFor(aliceUid, utf8("six")))) == "six")

            val short = alice.encryptFor(bobUid, utf8("x"))
            val medium = alice.encryptFor(bobUid, utf8("y".repeat(100)))
            check(short.size == medium.size) { "padding does not hide the length" }
            "sessions, skipped keys and identity restored from disk; messages padded"
        }

        step("Group chat survives a process kill") {
            val key = StateVault.newKey()
            val aliceStore = FileStateStore(File(workDir, "g-alice.bin").toPath())
            val bobStore = FileStateStore(File(workDir, "g-bob.bin").toPath())
            var alice = StateRepository.open(aliceStore, key)
            var bob = StateRepository.open(bobStore, key)
            val aliceUid = alice.state.identity.uid()
            val bobUid = bob.state.identity.uid()

            val created = alice.state.groups.createGroup(listOf(GroupManager.GroupMember(bob.state.identity.sigPub())))
            val groupId = created.groupId()
            val follow = bob.state.groups.handleControl(aliceUid, created.outbound()[0].plaintext())
            alice.state.groups.handleControl(bobUid, follow[0].plaintext())

            val first = alice.state.groups.encrypt(groupId, utf8("g1")).wire()
            val second = alice.state.groups.encrypt(groupId, utf8("g2")).wire()
            val third = alice.state.groups.encrypt(groupId, utf8("g3")).wire()
            check(groupText(bob.state.groups.decrypt(third)) == "g3")
            alice.save()
            bob.save()

            alice = StateRepository.open(aliceStore, key)
            bob = StateRepository.open(bobStore, key)
            check(groupText(bob.state.groups.decrypt(first)) == "g1") { "a skipped group key was lost" }
            check(groupText(bob.state.groups.decrypt(second)) == "g2")
            val fourth = alice.state.groups.encrypt(groupId, utf8("g4")).wire()
            check(groupText(bob.state.groups.decrypt(fourth)) == "g4")
            check(groupText(alice.state.groups.decrypt(bob.state.groups.encrypt(groupId, utf8("h1")).wire())) == "h1")
            "chains and skipped keys restored; both directions work"
        }

        return steps
    }

    fun keystoreStep(context: Context): Step {
        val name = "Android Keystore master key"
        return try {
            val first = KeystoreMasterKey.getOrCreate(context)
            val second = KeystoreMasterKey.getOrCreate(context)
            check(first.size == 32 && first.contentEquals(second)) { "the master key changed between two reads" }
            Step(name, true, "32-byte key, stable across reads")
        } catch (e: Throwable) {
            Step(name, false, e.toString())
        }
    }
}

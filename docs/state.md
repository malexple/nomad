# Client state and persistence (step 8.1)

## What is persisted
One `ClientState` object, written as one blob (`ClientState.seal` / `open`):
1. Identity: Ed25519 device key, X25519 identity key, signed prekeys (the last two), one-time prekeys not yet used.
2. Pairwise sessions: for every peer a list of Double Ratchet sessions (root, chain and ratchet keys, counters,
   skipped message keys, the pending X3DH header).
3. Groups: roster, admin, generation, own sending chain, receiving chains with skipped keys, sender keys that arrived early.
4. Inbox: read cursor (epoch, seq), the set of seen envelope ids, messages held until they can be decrypted.

Format: magic `NMD1`, format number, then the four sections (big endian, length-prefixed, see BinWriter/BinReader).
Any truncated or absurd data is rejected with an exception, never half-loaded.

## Encryption at rest
The blob is encrypted with `StateVault` (AES-256-GCM, random nonce, fixed AAD) under a 32-byte master key that the
platform supplies:
- JVM tools and tests: a key held in memory or derived from a passphrase.
- Android (step 8.2): a random master key wrapped by the Android Keystore (a hardware-backed AES key); no SQLCipher is needed
  because the whole state is one encrypted blob in a private file.

## Rules for the application (important)
- **Save before send.** A ratchet step or a sender-key step must reach the disk before the ciphertext leaves the device.
  Otherwise a crash after sending would roll the state back and the same message key could encrypt a second, different
  message: the key and nonce are derived from the message key, so this would break AES-GCM.
- **Save before acknowledging.** Acknowledge a message to the node only after the state that contains its effects is saved
  (the held messages are part of the state).
- Write the file atomically (`FileStateStore` writes a temporary file and moves it over the old one).
- A failed load (wrong key, damaged file) must not silently create a new identity: the user has to be told.
- The snapshot is taken while no message is being processed (components are written one after another).

## Check
`client-sim --encrypt true --group-size 4 --restart-user 2`: after round 1 a user is saved and closed, the others send
round 2, the user restarts from the saved state and sends its round 2. Nothing is lost, the uid is unchanged and the
restarted user fetches no new prekey bundle for peers it already knew.

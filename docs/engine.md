# Client engine (step 8.3a)

`NomadEngine` (module `client-core`) is the client logic that is identical on every platform. The simulator and the Android
app differ only in the `Transport` they plug in.

## Interfaces
- `Transport`: `connect(url, listener)` (asynchronous, every call is a NEW connection), `send(text)`, `close()`.
  `TransportListener`: `onConnected()`, `onText(text)`, `onClosed(reason)`. No reactive or platform types.
  Implementations: `JdkWebSocketTransport` (simulator), `OkHttpTransport` (Android).
- `Persistence.save()`: writes the whole client state (the app supplies `StateRepository.persistence`).
- `NomadEngine.Listener`: connection state, prekeys published, chat message, group message, groups changed, errors.
  Called on the engine thread: keep it short or hand the work over.

## Threading
One internal thread does everything. The public methods and the transport callbacks only post work to it, so there are no locks
and nothing blocks (a prekey lookup is a state machine: the message waits in memory until the keys arrive).

## What it does
- Connects, answers the challenge, reconnects by itself with back-off (1 s doubling to 30 s), ping every 30 s,
  safety-net sync every 60 s, `wake` triggers a sync at once.
- Uploads the prekeys once (the one-time keys are saved before they are published).
- Pairwise messages: asks for the bundle of an unknown peer, starts the session, encrypts, puts the envelope into the
  OUTBOX, SAVES, then sends. The outbox is part of the saved state: unacknowledged envelopes are resent after a reconnect
  or a restart (the node deduplicates by envelope id).
- Group messages and group control (state, sender keys); a group message waits until the keys of the group exist.
- Received messages: decrypts, holds what cannot be decrypted yet, retries after every control message, SAVES, and only then
  acknowledges to the node.
- `rate_limited` answers: the engine sends more slowly (55 ms between frames) and repeats; prekey lookups wait the given time.

## Public API (any thread)
`start()`, `stop()`, `shutdown()`, `sendChat(peerSigKey, text)`, `createGroup(members)`, `sendGroup(groupId, text)`,
`snapshot(masterKey)`, `groups()`, `connectionState()`, `outboxSize()`, `heldCount()`, `uid()`, `sigKey()`.
The futures complete when the message is safely in the outbox and saved, not when it has been delivered.

## Tests
`NomadEngineTest` plays the node with a fake transport: authentication, prekey upload after saving, chat with a bundle lookup
(the ciphertext is decrypted by the peer), save-before-send order, resend after a reconnect and after a restart,
acknowledgement only after saving, duplicates ignored, an unknown recipient fails the send.
The simulator runs the same engine at scale.

## State format
The outbox and the "prekeys published" flag are new, and the sessions now remember the peer's signing key, so the state format
is 2. A state file written by an older build cannot be read: on the phone clear the app data (or uninstall the app) once.

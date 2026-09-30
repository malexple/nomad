# Roadmap and acceptance checks

Principle: small steps, every step ends with a check that anyone can repeat.
Priority = value for the family MVP divided by risk. Servers in RU are not chosen yet, so local work
(keys, encryption, groups, Android) goes before deployment.

| # | Step | Status | Acceptance check |
|---|---|---|---|
| 0 | Skeleton, REST, mailbox | done | `./gradlew test` is green |
| 1 | Netty WebSocket gateway, RedisWakeBus, simulator | done | see "Results" |
| 4 | Prekey directory over WebSocket, PostgreSQL and in-memory | done | encrypted simulator works across two nodes |
| 5 | E2EE 1:1: X3DH + Double Ratchet behind `E2eeSession` | done | tests green, `--encrypt true` OK on 1 and 2 nodes |
| 6 | Groups: Sender Keys, admin-only membership, signed messages, rotation | done | `GroupManagerTest` green; `--group-size 5` OK on two nodes |
| 7 | Limits (token bucket per device, prekey lookups per target), padding | done | load run `--messages 200`: `RESULT: OK`, `rateLimited` > 0 |
| 8.0 | Portability of the shared modules (no `HexFormat`, no JDK Ed25519, Java 17 release, REST slice and CLI removed) | done | tests green; simulator unchanged |
| 8.1 | State persistence (docs/state.md): binary format, `StateVault` (AES-GCM), `StateStore`/`FileStateStore`, `ClientState` | done | persistence tests green; `--restart-user 2` run prints `RESULT: OK` |
| 8.2 | Android project skeleton (Kotlin, minSdk 26, core library desugaring), shared modules as dependencies, master key in Android Keystore, state in a private file, save-before-send / save-before-ack | next | instrumented test: encrypt, kill the process, decrypt after restart |
| 8.3 | Android UI MVP (Compose): contacts by invitation, chats, group chat, foreground connection | planned | phone to simulator user and back, offline delivery after reconnect |
| 2 | Deploy a node: Dockerfile, compose, TLS via reverse proxy, WireGuard between servers | waits for servers | simulator from a laptop against the public address is OK |
| 3 | PostgreSQL replication and a failover drill (docs/failover.md) | waits for servers | kill the primary, promote, epoch+1, the simulator recovers |
| 9 | Push relay (RuStore/APNs/UnifiedPush) with push_id | planned | message arrives with the app killed, the provider payload is empty |
| 10 | Media and voice: encrypted blobs with TTL | planned | 5 MB file goes through, the node stores an opaque blob |
| 11 | Modes "Simple" and "Guardian", first client analyzer | planned | parent sees a signal without the message text |
| 12 | Transport plugins and bridges | planned | switching transport by probe(), tested with a blocked port |
| 13 | Transparency log and witnesses | later | needed only with outside users |

## Decisions
- Two separate key pairs per device: Ed25519 (identity, signing, uid) and X25519 (identity for X3DH).
  The X25519 keys are signed by Ed25519, so a node cannot substitute them undetected. No XEdDSA.
- X3DH and Double Ratchet follow the Signal specifications; only the identity-key handling differs (above).
- Own crypto primitives are never written: X25519 and Ed25519 from Bouncy Castle (plain byte arrays), HMAC/HKDF/AES-GCM from the JCE.
- The node is untrusted for content: it stores ciphertext and public prekeys only.
- MailboxStore is the source of truth, Redis only wakes; clients keep a periodic sync as a safety net.
- The one-time prekey is spent only after the first message is authenticated.
- Groups (docs/groups.md): admin-only membership, every message signed by its sender, one copy per member.
- A message that cannot be decrypted yet is held, and the acknowledgement stops below it.
- Limits are per device and answered with `rate_limited` (docs/limits.md); messages are padded to size buckets.
- Shared modules (core, crypto, client-core) are compiled with `--release 17` and avoid APIs missing on Android.
- The client state is one sealed blob (docs/state.md); no SQLCipher. Invariants: save before send, save before acknowledging.
- MLS stays possible later: the scheme version is in the envelope (`v`): 0 = dev plaintext, 1 = X3DH + Double Ratchet,
  2 = group message (Sender Keys).

## Results
- Step 1 (local, two nodes, PostgreSQL, Redis): 300/300 with wake only; Redis outage drills OK; a node starts without Redis.
- Steps 4 and 5: 6 users on two nodes, 120/120 encrypted, `undecryptable=0`.
- Step 6: 6 users, group of 5, 260/260 on two nodes; nothing held at the end.
- Step 7: normal run 160/160 with `rateLimited=0`; load runs 1200/1200 and 1300/1300 with about 870-890 refusals.
- Step 8.0: 4 users, group of 4, 80/80 on two nodes after the key refactoring.

## Known gaps
- The sender id is not hidden from the recipient's node in the initial pairwise header (sealed sender is later).
- The simulator keeps the state in memory only; the save-before-send rule is enforced by the Android client (8.2).
- Many devices can still drain someone's one-time prekeys: registration must be gated (invitations).
- Not audited. Do not use for anything that needs real secrecy.

Rules for every step: a test or a script that proves it, a short note in docs/, a git tag.

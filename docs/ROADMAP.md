# Roadmap and acceptance checks

Principle: small steps, every step ends with a check that anyone can repeat.
Priority = value for the family MVP divided by risk. Servers in RU are not chosen yet, so local work
(keys, encryption, groups, Android) goes before deployment.

| # | Step | Status | Acceptance check |
|---|---|---|---|
| 0 | Skeleton, REST, mailbox | done | `./gradlew test` is green |
| 1 | Netty WebSocket gateway, RedisWakeBus, simulator | done | see "Results" |
| 4 | Prekey directory over WebSocket, PostgreSQL and in-memory | done | encrypted simulator works across two nodes |
| 5 | E2EE 1:1: X3DH + Double Ratchet behind `E2eeSession` | done | tests green, simulator OK on 1 and 2 nodes |
| 6 | Groups: Sender Keys, admin-only membership, signed messages, rotation | done | `GroupManagerTest` green; `--group-size 5` OK on two nodes |
| 7 | Limits (token bucket per device, prekey lookups per target), padding | done | load run `--messages 200`: `RESULT: OK`, `rateLimited` > 0 |
| 8.0 | Portability of the shared modules | done | tests green; simulator unchanged |
| 8.1 | State persistence (docs/state.md) | done | `--restart-user 2` run: 160/160, 0 bundles fetched after restart |
| 8.2 | Android project (docs/android.md): Keystore master key, encrypted state file, on-device self-test | done | `DeviceCryptoTest` 2/2 on Galaxy Note 10 (Android 12); self-test screen 5 x PASS |
| 8.3a | Client engine `NomadEngine` in client-core (docs/engine.md); the simulator rewritten on top of it; `OkHttpTransport` for Android | in progress | `NomadEngineTest` green; all simulator checks (2 nodes, group, restart, load) give `RESULT: OK` on the engine |
| 8.3b | First screens (Compose, Russian): identity and invitation link, adding a contact by link, chat list, chat, group creation; connection while the app is open | next | phone to simulator user and back over the home Wi-Fi, offline delivery after reconnect |
| 2 | Deploy a node: Dockerfile, compose, TLS via reverse proxy, WireGuard between servers | waits for servers | simulator from a laptop against the public address is OK |
| 3 | PostgreSQL replication and a failover drill (docs/failover.md) | waits for servers | kill the primary, promote, epoch+1, the simulator recovers |
| 9 | Push relay (RuStore/APNs/UnifiedPush) with push_id, background connection | planned | message arrives with the app killed, the provider payload is empty |
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
- Android (docs/android.md): a separate Gradle build in `android/`; the shared modules are compiled into the app from their
  source folders; minSdk 30 (Android 11); the master key of the state file is wrapped by the Android Keystore; no backup.
- One client engine (docs/engine.md): a single thread, a tiny `Transport` interface, an outbox that is part of the saved state.
  The plaintext scheme v0 is gone: everything is encrypted.
- Invitations: a link that carries the node address and the public signing key; shown as text and as QR (scanning later).
- The app is in Russian; the connection lives while the app is open (background work comes with push, step 9).
- MLS stays possible later: the scheme version is in the envelope (`v`): 1 = X3DH + Double Ratchet, 2 = group message (Sender Keys).

## Results
- Step 1 (local, two nodes, PostgreSQL, Redis): 300/300 with wake only; Redis outage drills OK; a node starts without Redis.
- Steps 4 and 5: 6 users on two nodes, 120/120 encrypted, `undecryptable=0`.
- Step 6: 6 users, group of 5, 260/260 on two nodes; nothing held at the end.
- Step 7: normal run 160/160 with `rateLimited=0`; load runs 1200/1200 and 1300/1300 with about 870-890 refusals.
- Step 8.1: user 2 killed and restarted from its saved state in the middle of the run: 160/160, 0 bundles fetched after the restart.
- Step 8.2: Galaxy Note 10 (SM-N971N, Android 12): 2/2 instrumented tests, self-test screen all PASS (9, 8, 122, 68 ms).

## Known gaps
- The sender id is not hidden from the recipient's node in the initial pairwise header (sealed sender is later).
- Many devices can still drain someone's one-time prekeys: registration must be gated (invitations).
- If the Keystore key of the device is lost, the state cannot be opened (by design there is no silent new identity);
  a recovery path (invitation again, new identity announced to the contacts) is still to be designed.
- Not audited. Do not use for anything that needs real secrecy.

Rules for every step: a test or a script that proves it, a short note in docs/, a git tag.

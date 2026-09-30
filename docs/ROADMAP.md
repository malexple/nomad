# Roadmap and acceptance checks

Principle: small steps, every step ends with a check that anyone can repeat.
Priority = value for the family MVP divided by risk. Servers in RU are not chosen yet, so local work
(keys, encryption, groups, Android) goes before deployment.

| # | Step | Status | Acceptance check |
|---|---|---|---|
| 0 | Skeleton, REST, mailbox | done | `./gradlew test` is green |
| 1 | Netty WebSocket gateway, RedisWakeBus, simulator | done | see "Results of step 1" below |
| 4 | Prekey directory (uid to public keys) over WebSocket, PostgreSQL and in-memory | done | `pk_put` / `pk_get` work on two nodes, one-time prekeys are handed out once |
| 5 | E2EE 1:1: X3DH + Double Ratchet (Bouncy Castle X25519, JCE for the rest) behind `E2eeSession` | done | `crypto` tests green (RFC vectors, out-of-order, tamper, replay, glare); simulator `--encrypt true` prints `RESULT: OK` on two nodes |
| 6 | Groups: Sender Keys, rotation when a member leaves | next | removed member cannot read new messages (test) |
| 7 | Limits: token bucket per device, size buckets (padding), rate limit on `pk_get` | planned | simulator at 10x load does not crash the node; OPK pool cannot be drained by one device |
| 8 | Android client (Kotlin) MVP: keystore, SQLCipher, WebSocket, foreground connection | planned | phone to CLI and back, offline delivery after reconnect |
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
- Own crypto primitives are never written: X25519 from Bouncy Castle, HMAC/HKDF/AES-GCM/Ed25519 from the JDK.
- The node is untrusted for content: it stores ciphertext and public prekeys only.
- MailboxStore is the source of truth, Redis only wakes; clients keep a periodic sync as a safety net.
- MLS stays possible later: the scheme version is in the envelope (`v`): 0 = dev plaintext, 1 = X3DH + Double Ratchet.

## Results of step 1 (local, two nodes, PostgreSQL, Redis)
- 6 users, 300 messages, wake only (`--poll-sec 0`): 300/300, no duplicates.
- Redis stopped while the simulator runs, `--poll-sec 5`: everything delivered by the periodic sync.
- Redis stopped and started again during the run, `--poll-sec 0`: 180/180, tail latency about 1 s, log line
  "Redis reconnected, waking all local sessions".
- A node starts without Redis and connects to it in the background.

## Known gaps of the current E2EE (each has a planned step)
- The sender id is not hidden from the recipient's node in the initial header (sealed sender is later).
- No padding yet (step 7). Session state is kept in memory only (persistence with SQLCipher in step 8).
- An undecryptable message is currently acknowledged and lost; real clients must keep it until it can be decrypted.
- A device that publishes prekeys can be asked for bundles without limit (rate limit in step 7).
- Not audited. Do not use for anything that needs real secrecy.

Rules for every step: a test or a script that proves it, a short note in docs/, a git tag.

# Roadmap and acceptance checks

Principle: small steps, every step ends with a check that anyone can repeat.
Priority = value for the family MVP divided by risk. Servers in RU are not chosen yet, so local work
(keys, encryption, groups, Android) goes before deployment.

| # | Step | Status            | Acceptance check |
|---|---|-------------------|---|
| 0 | Skeleton, REST, mailbox | done              | `./gradlew test` is green |
| 1 | Netty WebSocket gateway, RedisWakeBus, simulator | done              | see "Results" below |
| 4 | Prekey directory (uid to public keys) over WebSocket, PostgreSQL and in-memory | done              | encrypted simulator works across two nodes |
| 5 | E2EE 1:1: X3DH + Double Ratchet (Bouncy Castle X25519, JCE for the rest) behind `E2eeSession` | done              | tests green, `--encrypt true` OK on 1 and 2 nodes |
| 6 | Groups: Sender Keys, admin-only membership, signed messages, rotation on change; held messages instead of lost ones | done              | `GroupManagerTest` green; simulator `--encrypt true --group-size 4` prints `RESULT: OK` on two nodes |
| 7 | Limits: token bucket per device, size buckets (padding), rate limit on `pk_get` | planned           | simulator at 10x load does not crash the node; OPK pool cannot be drained by one device |
| 8 | Android client (Kotlin) MVP: keystore, SQLCipher (sessions and held messages persist), WebSocket, foreground connection | planned           | phone to CLI and back, offline delivery after reconnect, restart keeps sessions |
| 2 | Deploy a node: Dockerfile, compose, TLS via reverse proxy, WireGuard between servers | waits for servers | simulator from a laptop against the public address is OK |
| 3 | PostgreSQL replication and a failover drill (docs/failover.md) | waits for servers | kill the primary, promote, epoch+1, the simulator recovers |
| 9 | Push relay (RuStore/APNs/UnifiedPush) with push_id | planned           | message arrives with the app killed, the provider payload is empty |
| 10 | Media and voice: encrypted blobs with TTL | planned           | 5 MB file goes through, the node stores an opaque blob |
| 11 | Modes "Simple" and "Guardian", first client analyzer | planned           | parent sees a signal without the message text |
| 12 | Transport plugins and bridges | planned           | switching transport by probe(), tested with a blocked port |
| 13 | Transparency log and witnesses | later             | needed only with outside users |

## Decisions
- Two separate key pairs per device: Ed25519 (identity, signing, uid) and X25519 (identity for X3DH).
  The X25519 keys are signed by Ed25519, so a node cannot substitute them undetected. No XEdDSA.
- X3DH and Double Ratchet follow the Signal specifications; only the identity-key handling differs (above).
- Own crypto primitives are never written: X25519 from Bouncy Castle, HMAC/HKDF/AES-GCM/Ed25519 from the JDK.
- The node is untrusted for content: it stores ciphertext and public prekeys only.
- MailboxStore is the source of truth, Redis only wakes; clients keep a periodic sync as a safety net.
- The one-time prekey is spent only after the first message is authenticated (a forged first message must not burn it).
- Groups (docs/groups.md): admin-only membership, every message signed by its sender, one copy per member.
- A message that cannot be decrypted yet is held, and the acknowledgement stops below it (nothing is silently lost).
- MLS stays possible later: the scheme version is in the envelope (`v`): 0 = dev plaintext, 1 = X3DH + Double Ratchet,
  2 = group message (Sender Keys).

## Results
Step 1 (local, two nodes, PostgreSQL, Redis): 300/300 with wake only; Redis outage drills OK (see docs/failover.md
and the simulator options `--poll-sec`, `--delay-ms`); a node starts without Redis.
Steps 4 and 5: 3 users on one node, p50 16 ms; 6 users on two nodes, 120/120 encrypted, p50 263 ms, `undecryptable=0`.

## Known gaps
- The sender id is not hidden from the recipient's node in the initial pairwise header (sealed sender is later).
- No padding yet (step 7). Session state and held messages are kept in memory only (SQLCipher in step 8).
- Prekey requests are not rate limited yet (step 7).
- Not audited. Do not use for anything that needs real secrecy.

Rules for every step: a test or a script that proves it, a short note in docs/, a git tag.

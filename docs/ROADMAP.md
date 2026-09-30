# Roadmap and acceptance checks

Principle: small steps, every step ends with a check that anyone can repeat.
Priority = value for the family MVP divided by risk.

| # | Step | Result | Acceptance check |
|---|---|---|---|
| 0 | Skeleton, REST, mailbox (done) | build passes | `./gradlew test` is green, alice sends to bob via CLI |
| 1 | WebSocket gateway (Netty) + RedisWakeBus + simulator (this step) | messages travel without polling, across two nodes | `client-sim` prints `RESULT: OK` with 2 nodes, 5 users, 100 messages |
| 2 | Deploy a node: Dockerfile, compose, TLS via reverse proxy, WireGuard between servers | two servers in RU behave like one system | the simulator from a laptop against the public address is OK |
| 3 | PostgreSQL replication and a failover drill | procedure from docs/failover.md is proven | kill the primary, promote, epoch+1, the simulator recovers without losing acknowledged messages |
| 4 | Prekey directory + invitations (QR): how Alice learns Bob's keys | contacts can be added | two CLI users add each other by invite |
| 5 | E2EE: X3DH + Double Ratchet on Bouncy Castle behind E2eeSession, test vectors, out-of-order tests | the node sees only ciphertext | the simulator with encryption is OK, tests for lost/reordered messages are green |
| 6 | Groups: Sender Keys, rotation when a member leaves | family chat | removed member cannot read new messages (test) |
| 7 | Limits: token bucket per device, envelope size buckets (padding) | basic abuse protection | the simulator with 10x load does not crash the node |
| 8 | Android client (Kotlin) MVP: keystore, SQLCipher, WebSocket, notification from the foreground connection | a real phone talks to the system | phone to CLI and back, offline delivery after reconnect |
| 9 | Push relay (RuStore/APNs/UnifiedPush) with push_id | notifications when the app is closed | message arrives with the app killed; the provider payload is empty |
| 10 | Media and voice: encrypted blobs with TTL | photos and voice messages | 5 MB file goes through, the node stores an opaque blob |
| 11 | Modes "Simple" and "Guardian", first client analyzer | the reason for the project | parent sees a signal without the message text |
| 12 | Transport plugins and bridges | resistance to blocking | switching transport by probe(), tested with a blocked port |
| 13 | Transparency log and witnesses | verifiability | later, needed only with outside users |

Rules for every step: a test or a script that proves it, a short note in docs/, a git tag.

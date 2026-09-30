# nomad (working name)

Open messenger protocol. **Draft 0.1, not audited.** End-to-end encryption (X3DH + Double Ratchet, docs/e2ee.md) and
group chats (Sender Keys, docs/groups.md); scheme version 0 is dev plaintext. Plan: docs/ROADMAP.md,
WebSocket protocol: docs/ws-protocol.md.

## Modules
core, crypto (X3DH, Double Ratchet, ConversationManager, GroupManager), mailbox (envelopes + prekey directory),
bus (Local/Redis), server (REST + Netty WebSocket gateway), client-core, client-cli, client-sim.

## Check 1: unit tests
    ./gradlew test

## Check 2: one node, in memory
    ./gradlew :server:bootRun
    ./gradlew :client-sim:run --args="--users 3 --messages 10 --encrypt true"
    ./gradlew :client-sim:run --args="--users 4 --messages 5 --encrypt true --group-size 4 --group-messages 5"

## Check 3: two nodes, PostgreSQL, Redis (encryption across nodes needs the prekey directory in PostgreSQL)
    docker compose -f docker-compose.dev.yml up -d
    # terminal 1
    ./gradlew :server:bootRun --args="--server.port=8080 --nomad.ws.port=8090 --nomad.store=postgres --nomad.bus=redis"
    # terminal 2
    ./gradlew :server:bootRun --args="--server.port=8081 --nomad.ws.port=8091 --nomad.store=postgres --nomad.bus=redis"
    # terminal 3
    ./gradlew :client-sim:run --args="--urls ws://localhost:8090/v1/ws,ws://localhost:8091/v1/ws --users 6 --messages 10 --encrypt true --group-size 5 --group-messages 10"

Users are spread round-robin over the nodes. Outage drills (stop Redis while the simulator runs): docs/ROADMAP.md.
Failover: docs/failover.md.

## License
Code: Apache-2.0 (add the full LICENSE text). Specification text: CC BY 4.0.

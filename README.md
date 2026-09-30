# nomad (working name)

Open messenger protocol. **Draft 0.1, not audited.** Encryption is NOT implemented yet (`DevPlaintextSession`),
do not use it for real messages. Plan: docs/ROADMAP.md, WebSocket protocol: docs/ws-protocol.md.

## Modules
core, crypto, mailbox, bus (Local/Redis), server (REST + Netty WebSocket gateway), client-core, client-cli, client-sim.

## Check 1: one node, in memory
    ./gradlew test
    ./gradlew :server:bootRun
    ./gradlew :client-sim:run --args="--users 3 --messages 10"      # expect RESULT: OK

## Check 2: two nodes, PostgreSQL, Redis
    docker compose -f docker-compose.dev.yml up -d
    ./gradlew :server:bootRun --args="--server.port=8080 --nomad.ws.port=8090 --nomad.store=postgres --nomad.bus=redis"
    # second terminal
    ./gradlew :server:bootRun --args="--server.port=8081 --nomad.ws.port=8091 --nomad.store=postgres --nomad.bus=redis"
    # third terminal
    ./gradlew :client-sim:run --args="--urls ws://localhost:8090/v1/ws,ws://localhost:8091/v1/ws --users 6 --messages 50"

Users are spread round-robin over the nodes, so most messages cross from one node to the other through Redis.
Stop Redis (`docker compose stop redis`) while the simulator runs and start it again: messages still arrive
(on the next sync), because Redis is only a wake-up hint.

CLI over REST (v0): see client-cli. Failover: docs/failover.md.

## License
Code: Apache-2.0 (add the full LICENSE text). Specification text: CC BY 4.0.

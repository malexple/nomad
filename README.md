# nomad (working name)

Open messenger protocol. **Draft 0.1, not audited.** v0 slice: signed REST + mailbox. Encryption is NOT implemented yet
(`DevPlaintextSession`), do not use it for real messages.

## Modules
core, crypto (E2eeSession), mailbox (MailboxStore), bus (WakeBus), server (Spring Boot), client-core, client-cli.

## Start
    gradle wrapper --gradle-version 8.10
    ./gradlew test
    ./gradlew :server:bootRun                     # in-memory store
    # in another terminal
    export NOMAD_KEY=alice.key; ./gradlew :client-cli:run --args="keygen"
    ./gradlew :client-cli:run --args="whoami"
    export NOMAD_KEY=bob.key;   ./gradlew :client-cli:run --args="keygen"
    ./gradlew :client-cli:run --args="send <ALICE_MAILBOX> hello"
    export NOMAD_KEY=alice.key; ./gradlew :client-cli:run --args="recv"

PostgreSQL: `docker compose -f docker-compose.dev.yml up -d` and start the server with
`--nomad.store=postgres`. Failover: see docs/failover.md.

## License
Code: Apache-2.0 (add the full LICENSE text). Specification text: CC BY 4.0.

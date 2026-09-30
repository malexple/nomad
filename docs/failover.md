# Failover PostgreSQL (manual, family MVP)

Topology: primary on server 1, asynchronous streaming replica on server 2, gateways on both servers,
PostgreSQL and Redis reachable only through a private tunnel (WireGuard).

## Why an epoch is needed
Asynchronous replication can lose the last committed writes at failover. After promoting the replica the
client's cursor (seq) can be AHEAD of the new primary. Therefore:

1. `node_epoch.epoch` is stored in the database and returned with every read.
2. After promotion an administrator increments it once: `update node_epoch set epoch = epoch + 1 where id = 1;`
   (or call `PostgresMailboxStore.bumpEpoch()`).
3. A client that sees a different epoch resets its cursor to 0 and re-reads; duplicates are dropped by envelopeId
   (see `CursorState`). Envelopes the client sent but did not get a seq for are simply resent (idempotent by id).

## Steps
1. Make sure server 1 is really down (avoid two primaries).
2. On server 2: `SELECT pg_promote();`
3. Increment the epoch (see above).
4. Point the gateways to server 2 (config or DNS).
5. Later re-create server 1 as a replica of the new primary.

Trade-off: `synchronous_commit` with a synchronous standby removes loss but stops writes when the standby is unavailable.
For the family MVP the loss window is accepted and covered by the epoch mechanism.

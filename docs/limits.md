# Limits and padding (step 7)

## Per-device limits (token buckets, configurable in application.properties)
| Request | Limit | Property |
|---|---|---|
| `send` | 20 per second, burst 50 | `nomad.limits.send-per-sec`, `send-burst` |
| `sync`, `ack`, `pk_put` | 50 per second, burst 100 | `read-per-sec`, `read-burst` |
| `pk_get` (all targets) | 10 per minute | `pk-per-min` |
| `pk_get` (one target uid) | 3 per minute | `pk-target-per-min` |

A refused request is answered (the connection stays open):
`{"t":"error","msg":"rate_limited","retryMs":n}` plus `"id"` (a refused `send`) or `"uid"` (a refused `pk_get`).
The client repeats a refused `send` with the same id (the node deduplicates by id), one message at a time and slower than the
limit; a refused `sync` or `ack` is repeated after `retryMs`. A connection that keeps ignoring the limits (more than 500
refusals) is closed.

Why the per-target limit: a device could otherwise drain the one-time prekeys of someone else. A single device can no longer
do it quickly; an attacker with many devices still can, so registration must be gated later (invitations).

## Padding
Plaintexts (pairwise and group) are followed by 0x80 and zeros up to a bucket of 256, 1024, 4096 or 16384 bytes, then multiples
of 16384. The node sees the bucket, not the length. The wire length still differs between the initial pairwise message
(with the X3DH header), the normal one and the group message. Media are not padded here (they are separate blobs, later).

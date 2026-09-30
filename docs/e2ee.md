# End-to-end encryption v0 (scheme version 1)

Not audited. Follows the Signal specifications (X3DH, Double Ratchet) with two deliberate differences:
separate Ed25519 / X25519 identity keys, and AES-256-GCM with a key and nonce derived from each message key.

## Keys of a device
- Identity signing key Ed25519 (also used for WebSocket authentication). uid = base32(SHA-256(pub))[0..26].
- Identity DH key X25519, signed: `sigIk = Sign(ed, "nomad-ik-v0" || ikDh)`.
- Signed prekey X25519 with id, signed: `sigSpk = Sign(ed, "nomad-spk-v0" || id(4, BE) || spk)`. The last two are kept.
- One-time prekeys X25519 (pool of up to 100 on the node, each handed out once, private part deleted on use).

## Handshake (initiator A, responder B)
- A fetches B's bundle (`pk_get`), verifies both signatures and that `uid(sigKey)` is the uid it asked for.
- `DH1 = DH(IK_A, SPK_B)`, `DH2 = DH(EK_A, IK_B)`, `DH3 = DH(EK_A, SPK_B)`, `DH4 = DH(EK_A, OPK_B)` (if any).
- `SK = HKDF(salt = 32 zero bytes, ikm = 0xFF*32 || DH1..DH4, info = "nomad-x3dh-v0", 32 bytes)`.
- `AD = ikDhA || ikDhB || sigKeyA || sigKeyB`.

## Double Ratchet
- `KDF_RK = HKDF(salt = rk, ikm = dh_out, info = "nomad-dr-rk-v0", 64 bytes)`: new root key + chain key.
- `KDF_CK`: message key = HMAC-SHA256(ck, 0x01), next chain key = HMAC-SHA256(ck, 0x02).
- Message key to AES-256 key (32 bytes) and nonce (12 bytes): `HKDF(zero salt, mk, "nomad-dr-msg-v0", 44)`.
- Header (clear, authenticated as AAD together with AD): sender ratchet public key (32) | pn (4) | n (4).
- MAX_SKIP = 1000 per message, at most 2000 stored skipped keys. Decryption is atomic: a failed message changes nothing.

## Wire format of the envelope ciphertext
`type(1) [initial header 168 bytes] header(40) ciphertext+tag`
- type 1 = initial: sent with every message until the first answer from the peer arrives, so any of the first
  messages (even out of order) can start the session on the other side.
- type 2 = normal.
- initial header: sigKeyA(32) ikDhA(32) sigIkDhA(64) ekA(32) spkId(4) opkId(4, -1 = none).

## Sessions
- Several sessions per peer are allowed: both sides may start a conversation at the same moment ("glare").
  Both sessions stay usable, the most recently used one sends.
- Messages carry no sender id: a normal message is tried against the sessions.

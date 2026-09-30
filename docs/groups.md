# Group chats v0 (Sender Keys)

Not audited. Up to 10 members, one admin (who created the group). Design decisions:
- Only the admin changes the member list. Every change raises the generation.
- Every member has one sending chain per generation: chainId (random, 16 bytes), chain key (32 bytes), iteration.
  The node sees only the random chainId and the iteration, not the group id and not the sender.
- Chains are delivered through the pairwise Double Ratchet sessions:
  - `GROUP_STATE` (0x10): groupId, generation, list of member signing keys, the admin's chain. Accepted from the admin only
    (a first state for an unknown group is accepted from its sender, who becomes the admin: this is the invitation).
  - `SENDER_KEY` (0x11): groupId, generation, chainId, chain key, iteration. Accepted from members of the current generation.
    If the state has not arrived yet the key waits in a small buffer.
- After a new generation every member creates a new chain and sends it to all other members. A removed member gets nothing,
  so it cannot read new messages; its old chain is rejected because its uid is no longer in the roster.
- Chains of the previous generation are kept, so messages that were already in flight can still be read.
- A group message is encrypted once (AES-256-GCM, key and nonce from the chain step) and signed with the sender's
  Ed25519 identity key over `domain || groupId || chainId || iteration || ciphertext`; one copy goes to every member's mailbox.
  Wire (envelope `v` = 2): `chainId(16) | iteration(4) | ciphertext+tag | signature(64)`.
- The signature is checked before the chain state is touched, so a forged message cannot move the chain.
- A message whose chain is not known yet is held by the client and retried after every processed control message.
  The client acknowledges to the node only up to the earliest held message (`WsInbox.safeAckSeq`).

Known gaps: the held messages live in memory only (persistence comes with the Android storage); the node can see the
iteration counter and who receives a copy; a new member cannot read older messages by design; no forward secrecy
inside one chain (a leaked chain key opens the later messages of that chain until the next generation).

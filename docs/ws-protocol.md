# WebSocket protocol v0 (draft)

Endpoint: `ws://host:8090/v1/ws`. Text frames, JSON, field `t` is the message type.
The scheme is not final; the encrypted payload (`ct`) is opaque to the node.

| Direction | Message | Meaning |
|---|---|---|
| S to C | `{"t":"challenge","nonce":hex}` | sent right after the handshake |
| C to S | `{"t":"auth","key":b64,"sig":b64}` | Ed25519 signature over `"NOMAD-WS-AUTH\n"+nonce`; key is the raw 32 bytes |
| S to C | `{"t":"auth_ok","mailbox":hex,"epoch":n}` | session is bound to the mailbox of the key |
| C to S | `{"t":"send","id":uuid,"mailbox":hex,"v":1,"ct":b64,"exp":epochDay}` | put an envelope into someone's mailbox (`v` = encryption scheme, 0 = dev plaintext) |
| S to C | `{"t":"ack","id":uuid,"seq":n,"dup":bool}` | stored (idempotent by id) |
| S to C | `{"t":"wake"}` | hint only: something new in your mailbox |
| C to S | `{"t":"sync","after":n,"limit":100}` | read by cursor |
| S to C | `{"t":"msgs","epoch":n,"more":bool,"items":[{"seq","id","v","ct","exp"}]}` | `more` = ask again |
| C to S | `{"t":"ack","upTo":n}` | delete everything up to seq |
| S to C | `{"t":"acked","deleted":n}` | |
| C to S | `{"t":"pk_put","bundle":{sigKey,ikDh,sigIk,spkId,spk,sigSpk},"opks":[{"id","pub"}]}` | publish own public prekeys (all base64); the bundle key must equal the session key |
| S to C | `{"t":"pk_ok","opks":n}` | number of one-time prekeys left in the pool |
| C to S | `{"t":"pk_get","uid":text}` | fetch the bundle of a device, one one-time prekey is consumed |
| S to C | `{"t":"pk","uid":text,"bundle":{...,"opk":{"id","pub"}}}` or `{"t":"pk_none","uid":text}` | the client verifies the signatures itself |
| C to S | `{"t":"ping"}` | send about every 30 s (the server closes idle connections after 120 s) |
| S to C | `{"t":"error","msg":text}` | before auth the connection is closed |

Rules: the client syncs after `auth_ok`, after every `wake` and while `more` is true. A lost `wake` loses nothing.
Messages of one connection are processed on virtual threads, so send the next request after the reply.

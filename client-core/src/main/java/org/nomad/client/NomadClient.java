package org.nomad.client;

import com.fasterxml.jackson.annotation.JsonIgnoreProperties;
import com.fasterxml.jackson.databind.ObjectMapper;
import java.io.IOException;
import java.nio.charset.StandardCharsets;
import java.security.KeyPair;
import java.time.LocalDate;
import java.util.ArrayList;
import java.util.Base64;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.UUID;
import org.nomad.core.DeviceAuth;
import org.nomad.core.Ids;

/** Signs requests, sends envelopes, polls by cursor. Encryption is done by the layer above (E2eeSession). */
public final class NomadClient {
    @JsonIgnoreProperties(ignoreUnknown = true)
    record PutBody(UUID envelopeId, int version, String mailboxId, String ciphertext, long expiryDay) {}

    @JsonIgnoreProperties(ignoreUnknown = true)
    record PutReply(long seq, boolean duplicate) {}

    @JsonIgnoreProperties(ignoreUnknown = true)
    record ItemBody(long seq, UUID envelopeId, int version, String ciphertext, long expiryDay) {}

    @JsonIgnoreProperties(ignoreUnknown = true)
    record ReadReply(long epoch, List<ItemBody> items) {}

    private static final int TTL_DAYS = 30;

    private final HttpPort http;
    private final KeyPair device;
    private final ObjectMapper mapper = new ObjectMapper();

    public NomadClient(HttpPort http, KeyPair device) {
        this.http = http;
        this.device = device;
    }

    public String uid() {
        return Ids.uid(DeviceAuth.rawPublicKey(device.getPublic()));
    }

    public String mailboxId() {
        return Ids.mailboxIdFor(DeviceAuth.rawPublicKey(device.getPublic()));
    }

    public long send(String toMailboxId, int schemeVersion, byte[] ciphertext) throws IOException {
        long expiry = LocalDate.now().plusDays(TTL_DAYS).toEpochDay();
        PutBody body = new PutBody(
                UUID.randomUUID(), schemeVersion, toMailboxId, Base64.getEncoder().encodeToString(ciphertext), expiry);
        HttpPort.Response r = call("POST", "/v1/envelopes", mapper.writeValueAsBytes(body));
        return mapper.readValue(r.body(), PutReply.class).seq();
    }

    public List<Received> poll(CursorState cursor, int limit) throws IOException {
        ReadReply reply = fetch(cursor.seq(), limit);
        if (cursor.epoch() != 0 && reply.epoch() != cursor.epoch()) {
            cursor.setSeq(0);
            reply = fetch(0, limit);
        }
        cursor.setEpoch(reply.epoch());
        List<Received> out = new ArrayList<>();
        for (ItemBody it : reply.items()) {
            cursor.setSeq(Math.max(cursor.seq(), it.seq()));
            if (cursor.markSeen(it.envelopeId())) {
                out.add(new Received(it.seq(), it.envelopeId(), it.version(), Base64.getDecoder().decode(it.ciphertext())));
            }
        }
        return out;
    }

    public void ack(CursorState cursor) throws IOException {
        call("DELETE", "/v1/mailboxes/" + mailboxId() + "/envelopes?upTo=" + cursor.seq(), new byte[0]);
    }

    private ReadReply fetch(long after, int limit) throws IOException {
        String path = "/v1/mailboxes/" + mailboxId() + "/envelopes?after=" + after + "&limit=" + limit;
        return mapper.readValue(call("GET", path, new byte[0]).body(), ReadReply.class);
    }

    private HttpPort.Response call(String method, String pathAndQuery, byte[] body) throws IOException {
        long ts = System.currentTimeMillis() / 1000;
        byte[] sig = DeviceAuth.sign(device.getPrivate(), DeviceAuth.canonical(method, pathAndQuery, ts, body));
        Map<String, String> h = new LinkedHashMap<>();
        h.put("X-Device-Key", Base64.getEncoder().encodeToString(DeviceAuth.rawPublicKey(device.getPublic())));
        h.put("X-Timestamp", Long.toString(ts));
        h.put("X-Signature", Base64.getEncoder().encodeToString(sig));
        if (body.length > 0) {
            h.put("Content-Type", "application/json");
        }
        HttpPort.Response r = http.execute(method, pathAndQuery, h, body);
        if (r.status() / 100 != 2) {
            throw new IOException("HTTP " + r.status() + ": " + new String(r.body(), StandardCharsets.UTF_8));
        }
        return r;
    }
}

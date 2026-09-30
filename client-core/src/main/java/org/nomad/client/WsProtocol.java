package org.nomad.client;

import com.fasterxml.jackson.core.JsonProcessingException;
import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.fasterxml.jackson.databind.node.ObjectNode;
import java.security.KeyPair;
import java.time.LocalDate;
import java.util.ArrayList;
import java.util.Base64;
import java.util.List;
import java.util.UUID;
import org.nomad.core.DeviceAuth;

/** Builds and parses the WebSocket JSON messages (docs/ws-protocol.md). Transport-agnostic. */
public final class WsProtocol {
    private static final ObjectMapper M = new ObjectMapper();

    private WsProtocol() {}

    public record Msgs(long epoch, boolean more, List<Received> items) {}

    public static String auth(KeyPair kp, String nonce) {
        byte[] sig = DeviceAuth.sign(kp.getPrivate(), "NOMAD-WS-AUTH\n" + nonce);
        ObjectNode o = M.createObjectNode();
        o.put("t", "auth");
        o.put("key", Base64.getEncoder().encodeToString(DeviceAuth.rawPublicKey(kp.getPublic())));
        o.put("sig", Base64.getEncoder().encodeToString(sig));
        return o.toString();
    }

    public static String send(UUID id, String mailboxId, int schemeVersion, byte[] ciphertext, long expiryDay) {
        ObjectNode o = M.createObjectNode();
        o.put("t", "send");
        o.put("id", id.toString());
        o.put("mailbox", mailboxId);
        o.put("v", schemeVersion);
        o.put("ct", Base64.getEncoder().encodeToString(ciphertext));
        o.put("exp", expiryDay);
        return o.toString();
    }

    public static String sync(long afterSeq, int limit) {
        ObjectNode o = M.createObjectNode();
        o.put("t", "sync");
        o.put("after", afterSeq);
        o.put("limit", limit);
        return o.toString();
    }

    public static String ack(long upToSeq) {
        ObjectNode o = M.createObjectNode();
        o.put("t", "ack");
        o.put("upTo", upToSeq);
        return o.toString();
    }

    public static String ping() {
        return "{\"t\":\"ping\"}";
    }

    public static long defaultExpiryDay() {
        return LocalDate.now().plusDays(30).toEpochDay();
    }

    public static JsonNode parse(String text) {
        try {
            return M.readTree(text);
        } catch (JsonProcessingException e) {
            throw new IllegalArgumentException("invalid json", e);
        }
    }

    public static Msgs parseMsgs(JsonNode n) {
        List<Received> items = new ArrayList<>();
        for (JsonNode it : n.path("items")) {
            items.add(new Received(
                    it.path("seq").asLong(),
                    UUID.fromString(it.path("id").asText()),
                    it.path("v").asInt(),
                    Base64.getDecoder().decode(it.path("ct").asText())));
        }
        return new Msgs(n.path("epoch").asLong(), n.path("more").asBoolean(), items);
    }
}

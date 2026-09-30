package org.nomad.client;

import com.fasterxml.jackson.core.JsonProcessingException;
import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.fasterxml.jackson.databind.node.ArrayNode;
import com.fasterxml.jackson.databind.node.ObjectNode;
import java.security.KeyPair;
import java.time.LocalDate;
import java.util.ArrayList;
import java.util.Base64;
import java.util.List;
import java.util.UUID;
import org.nomad.core.DeviceAuth;
import org.nomad.core.OneTimePrekey;
import org.nomad.core.PrekeyBundle;

/** Builds and parses the WebSocket JSON messages (docs/ws-protocol.md). Transport-agnostic. */
public final class WsProtocol {
    private static final ObjectMapper M = new ObjectMapper();

    private WsProtocol() {}

    public record Msgs(long epoch, boolean more, List<Received> items) {}

    public static String auth(KeyPair kp, String nonce) {
        byte[] sig = DeviceAuth.sign(kp.getPrivate(), "NOMAD-WS-AUTH\n" + nonce);
        ObjectNode o = M.createObjectNode();
        o.put("t", "auth");
        o.put("key", b64(DeviceAuth.rawPublicKey(kp.getPublic())));
        o.put("sig", b64(sig));
        return o.toString();
    }

    public static String send(UUID id, String mailboxId, int schemeVersion, byte[] ciphertext, long expiryDay) {
        ObjectNode o = M.createObjectNode();
        o.put("t", "send");
        o.put("id", id.toString());
        o.put("mailbox", mailboxId);
        o.put("v", schemeVersion);
        o.put("ct", b64(ciphertext));
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

    public static String pkPut(PrekeyBundle identityPart, List<OneTimePrekey> oneTimePrekeys) {
        ObjectNode o = M.createObjectNode();
        o.put("t", "pk_put");
        o.set("bundle", bundleNode(identityPart));
        ArrayNode arr = o.putArray("opks");
        for (OneTimePrekey k : oneTimePrekeys) {
            ObjectNode x = arr.addObject();
            x.put("id", k.id());
            x.put("pub", b64(k.pub()));
        }
        return o.toString();
    }

    public static String pkGet(String uid) {
        ObjectNode o = M.createObjectNode();
        o.put("t", "pk_get");
        o.put("uid", uid);
        return o.toString();
    }

    public static PrekeyBundle parseBundle(JsonNode n) {
        Base64.Decoder d = Base64.getDecoder();
        OneTimePrekey opk = null;
        JsonNode k = n.path("opk");
        if (k.isObject()) {
            opk = new OneTimePrekey(k.path("id").asInt(), d.decode(k.path("pub").asText()));
        }
        return new PrekeyBundle(
                d.decode(n.path("sigKey").asText()),
                d.decode(n.path("ikDh").asText()),
                d.decode(n.path("sigIk").asText()),
                n.path("spkId").asInt(),
                d.decode(n.path("spk").asText()),
                d.decode(n.path("sigSpk").asText()),
                opk);
    }

    private static ObjectNode bundleNode(PrekeyBundle b) {
        ObjectNode n = M.createObjectNode();
        n.put("sigKey", b64(b.sigKey()));
        n.put("ikDh", b64(b.ikDh()));
        n.put("sigIk", b64(b.sigIkDh()));
        n.put("spkId", b.spkId());
        n.put("spk", b64(b.spk()));
        n.put("sigSpk", b64(b.sigSpk()));
        if (b.opk() != null) {
            ObjectNode k = n.putObject("opk");
            k.put("id", b.opk().id());
            k.put("pub", b64(b.opk().pub()));
        }
        return n;
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

    private static String b64(byte[] b) {
        return Base64.getEncoder().encodeToString(b);
    }
}

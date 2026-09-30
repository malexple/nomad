package org.nomad.server;

import com.fasterxml.jackson.databind.ObjectMapper;
import jakarta.servlet.http.HttpServletRequest;
import java.io.IOException;
import java.util.Base64;
import java.util.List;
import java.util.Map;
import java.util.UUID;
import org.nomad.bus.WakeBus;
import org.nomad.core.Envelope;
import org.nomad.core.Ids;
import org.nomad.mailbox.AppendResult;
import org.nomad.mailbox.MailboxStore;
import org.nomad.mailbox.ReadResult;
import org.springframework.http.HttpStatus;
import org.springframework.web.bind.annotation.DeleteMapping;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RequestParam;
import org.springframework.web.bind.annotation.RestController;
import org.springframework.web.server.ResponseStatusException;

/**
 * v0 REST slice. The WebSocket gateway (Netty) replaces polling later,
 * but the MailboxStore contract and cursors stay the same.
 */
@RestController
@RequestMapping("/v1")
class MailboxController {
    record PutRequest(UUID envelopeId, int version, String mailboxId, String ciphertext, long expiryDay) {}

    record PutResponse(long seq, boolean duplicate) {}

    record Item(long seq, UUID envelopeId, int version, String ciphertext, long expiryDay) {}

    record ReadResponse(long epoch, List<Item> items) {}

    private final MailboxStore store;
    private final WakeBus bus;
    private final DeviceAuthService auth;
    private final ObjectMapper mapper;

    MailboxController(MailboxStore store, WakeBus bus, DeviceAuthService auth, ObjectMapper mapper) {
        this.store = store;
        this.bus = bus;
        this.auth = auth;
        this.mapper = mapper;
    }

    @GetMapping("/health")
    Map<String, Object> health() {
        return Map.of("status", "ok", "epoch", store.epoch());
    }

    @PostMapping("/envelopes")
    PutResponse put(HttpServletRequest req, @RequestBody byte[] body) {
        auth.verify(req, body);
        Envelope env;
        try {
            PutRequest r = mapper.readValue(body, PutRequest.class);
            env = new Envelope(
                    r.version(), r.envelopeId(), r.mailboxId(), Base64.getDecoder().decode(r.ciphertext()), r.expiryDay());
        } catch (IOException | IllegalArgumentException e) {
            throw new ResponseStatusException(HttpStatus.BAD_REQUEST, "bad envelope: " + e.getMessage());
        }
        AppendResult res = store.append(env);
        if (!res.duplicate()) {
            bus.publish(env.mailboxId());
        }
        return new PutResponse(res.seq(), res.duplicate());
    }

    @GetMapping("/mailboxes/{mailboxId}/envelopes")
    ReadResponse read(
            HttpServletRequest req,
            @PathVariable String mailboxId,
            @RequestParam(defaultValue = "0") long after,
            @RequestParam(defaultValue = "100") int limit) {
        requireOwner(auth.verify(req, new byte[0]), mailboxId);
        ReadResult rr = store.read(mailboxId, after, Math.max(1, Math.min(limit, 500)));
        List<Item> items = rr.items().stream()
                .map(s -> new Item(
                        s.seq(),
                        s.envelope().envelopeId(),
                        s.envelope().version(),
                        Base64.getEncoder().encodeToString(s.envelope().ciphertext()),
                        s.envelope().expiryDay()))
                .toList();
        return new ReadResponse(rr.epoch(), items);
    }

    @DeleteMapping("/mailboxes/{mailboxId}/envelopes")
    Map<String, Integer> ack(HttpServletRequest req, @PathVariable String mailboxId, @RequestParam long upTo) {
        requireOwner(auth.verify(req, new byte[0]), mailboxId);
        return Map.of("deleted", store.deleteUpTo(mailboxId, upTo));
    }

    private static void requireOwner(DeviceAuthService.Authed a, String mailboxId) {
        if (!Ids.mailboxIdFor(a.rawKey()).equals(mailboxId)) {
            throw new ResponseStatusException(HttpStatus.FORBIDDEN, "not the owner of this mailbox");
        }
    }
}

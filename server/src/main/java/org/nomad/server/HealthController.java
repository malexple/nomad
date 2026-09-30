package org.nomad.server;

import java.util.Map;
import org.nomad.mailbox.MailboxStore;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RestController;

/** The only HTTP endpoint left: all messaging goes through the WebSocket gateway. */
@RestController
@RequestMapping("/v1")
class HealthController {
    private final MailboxStore store;

    HealthController(MailboxStore store) {
        this.store = store;
    }

    @GetMapping("/health")
    Map<String, Object> health() {
        return Map.of("status", "ok", "epoch", store.epoch());
    }
}

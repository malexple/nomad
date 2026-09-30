package org.nomad.mailbox;

import java.util.List;

/** epoch changes after a manual failover; see docs/failover.md. */
public record ReadResult(long epoch, List<StoredEnvelope> items) {}

package org.nomad.mailbox;

import org.nomad.core.Envelope;

public record StoredEnvelope(long seq, Envelope envelope) {}

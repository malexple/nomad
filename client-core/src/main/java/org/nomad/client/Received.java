package org.nomad.client;

import java.util.UUID;

public record Received(long seq, UUID envelopeId, int version, byte[] payload) {}

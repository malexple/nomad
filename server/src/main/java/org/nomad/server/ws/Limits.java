package org.nomad.server.ws;

/** Per-device limits: sends, reads (sync/ack/prekey publish) and prekey lookups (overall and per target). */
record Limits(
        double sendPerSec,
        double sendBurst,
        double readPerSec,
        double readBurst,
        double pkPerMin,
        double pkTargetPerMin) {}

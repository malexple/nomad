package org.nomad.mailbox;

import org.nomad.core.Envelope;

/**
 * Single source of truth for envelopes. Per mailbox, seq grows strictly in commit order,
 * so a reader with cursor afterSeq never skips an envelope.
 */
public interface MailboxStore {

    long epoch();

    /** Idempotent by envelopeId: a repeated append returns the original seq with duplicate=true. */
    AppendResult append(Envelope envelope);

    ReadResult read(String mailboxId, long afterSeq, int limit);

    /** Acknowledge: delete everything up to and including seq. */
    int deleteUpTo(String mailboxId, long seq);

    int purgeExpired(long todayEpochDay);
}

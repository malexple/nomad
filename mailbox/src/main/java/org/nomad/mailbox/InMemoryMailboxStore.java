package org.nomad.mailbox;

import java.util.ArrayList;
import java.util.HashMap;
import java.util.List;
import java.util.Map;
import java.util.UUID;
import org.nomad.core.Envelope;

/** For tests and local development. */
public final class InMemoryMailboxStore implements MailboxStore {
    private final Object lock = new Object();
    private long nextSeq = 1;
    private long epoch = 1;
    private final Map<UUID, Long> byId = new HashMap<>();
    private final List<StoredEnvelope> log = new ArrayList<>();

    @Override
    public long epoch() {
        synchronized (lock) {
            return epoch;
        }
    }

    public void bumpEpoch() {
        synchronized (lock) {
            epoch++;
        }
    }

    @Override
    public AppendResult append(Envelope e) {
        synchronized (lock) {
            Long existing = byId.get(e.envelopeId());
            if (existing != null) {
                return new AppendResult(existing, true);
            }
            long seq = nextSeq++;
            byId.put(e.envelopeId(), seq);
            log.add(new StoredEnvelope(seq, e));
            return new AppendResult(seq, false);
        }
    }

    @Override
    public ReadResult read(String mailboxId, long afterSeq, int limit) {
        synchronized (lock) {
            List<StoredEnvelope> out = new ArrayList<>();
            for (StoredEnvelope s : log) {
                if (out.size() >= limit) {
                    break;
                }
                if (s.seq() > afterSeq && s.envelope().mailboxId().equals(mailboxId)) {
                    out.add(s);
                }
            }
            return new ReadResult(epoch, out);
        }
    }

    @Override
    public int deleteUpTo(String mailboxId, long seq) {
        synchronized (lock) {
            return removeIf(s -> s.seq() <= seq && s.envelope().mailboxId().equals(mailboxId));
        }
    }

    @Override
    public int purgeExpired(long todayEpochDay) {
        synchronized (lock) {
            return removeIf(s -> s.envelope().expiryDay() < todayEpochDay);
        }
    }

    private int removeIf(java.util.function.Predicate<StoredEnvelope> p) {
        int before = log.size();
        log.removeIf(s -> {
            if (p.test(s)) {
                byId.remove(s.envelope().envelopeId());
                return true;
            }
            return false;
        });
        return before - log.size();
    }
}

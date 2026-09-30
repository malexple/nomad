package org.nomad.mailbox;

import java.util.List;
import java.util.Optional;
import org.nomad.core.OneTimePrekey;
import org.nomad.core.PrekeyBundle;

/** Public prekeys of devices, by uid. Holds only public data; the client verifies all signatures itself. */
public interface PrekeyDirectory {
    int MAX_ONE_TIME_PREKEYS = 100;

    /** Stores or replaces the identity part of the bundle and adds one-time prekeys (up to the cap). */
    void publish(PrekeyBundle identityPart, List<OneTimePrekey> newOneTimePrekeys);

    /** Returns the bundle with one one-time prekey removed from the pool (none if the pool is empty). */
    Optional<PrekeyBundle> fetch(String uid);

    int oneTimePrekeyCount(String uid);
}

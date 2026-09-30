package org.nomad.bus;

import java.util.function.Consumer;

/**
 * Best-effort "wake up": tells the node that holds the mailbox's WebSocket that there is something new.
 * A lost signal loses nothing: the client always reads from MailboxStore by cursor.
 */
public interface WakeBus {
    void publish(String mailboxId);

    /** @return a Runnable that cancels the subscription */
    Runnable subscribe(Consumer<String> listener);
}

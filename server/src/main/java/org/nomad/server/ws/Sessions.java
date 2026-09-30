package org.nomad.server.ws;

import io.netty.channel.Channel;
import io.netty.handler.codec.http.websocketx.TextWebSocketFrame;
import java.util.Set;
import java.util.concurrent.ConcurrentHashMap;

/** Local WebSocket sessions of this node, indexed by mailbox. */
final class Sessions {
    private final ConcurrentHashMap<String, Set<Channel>> byMailbox = new ConcurrentHashMap<>();

    void add(String mailbox, Channel ch) {
        byMailbox.computeIfAbsent(mailbox, k -> ConcurrentHashMap.newKeySet()).add(ch);
    }

    void remove(String mailbox, Channel ch) {
        byMailbox.computeIfPresent(mailbox, (k, set) -> {
            set.remove(ch);
            return set.isEmpty() ? null : set;
        });
    }

    /** Only a hint: the client answers with "sync" and reads from the store by cursor. */
    void wake(String mailbox) {
        Set<Channel> set = byMailbox.get(mailbox);
        if (set == null) {
            return;
        }
        for (Channel ch : set) {
            if (ch.isActive()) {
                ch.writeAndFlush(new TextWebSocketFrame("{\"t\":\"wake\"}"));
            }
        }
    }

    int mailboxes() {
        return byMailbox.size();
    }
}

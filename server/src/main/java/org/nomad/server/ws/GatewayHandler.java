package org.nomad.server.ws;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.fasterxml.jackson.databind.node.ArrayNode;
import com.fasterxml.jackson.databind.node.ObjectNode;
import io.netty.channel.ChannelFutureListener;
import io.netty.channel.ChannelHandler;
import io.netty.channel.ChannelHandlerContext;
import io.netty.channel.SimpleChannelInboundHandler;
import io.netty.handler.codec.http.websocketx.TextWebSocketFrame;
import io.netty.handler.codec.http.websocketx.WebSocketServerProtocolHandler;
import io.netty.handler.timeout.IdleStateEvent;
import io.netty.util.AttributeKey;
import java.io.IOException;
import java.security.PublicKey;
import java.security.SecureRandom;
import java.util.Base64;
import java.util.HexFormat;
import java.util.UUID;
import java.util.concurrent.ExecutorService;
import org.nomad.bus.WakeBus;
import org.nomad.core.DeviceAuth;
import org.nomad.core.Envelope;
import org.nomad.core.Ids;
import org.nomad.mailbox.AppendResult;
import org.nomad.mailbox.MailboxStore;
import org.nomad.mailbox.ReadResult;
import org.nomad.mailbox.StoredEnvelope;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

/**
 * Text-frame JSON protocol, see docs/ws-protocol.md. Store calls block (JDBC), so messages are
 * handled on virtual threads and never on the Netty event loop.
 */
@ChannelHandler.Sharable
final class GatewayHandler extends SimpleChannelInboundHandler<TextWebSocketFrame> {
    private static final Logger LOG = LoggerFactory.getLogger(GatewayHandler.class);
    private static final AttributeKey<String> NONCE = AttributeKey.valueOf("nonce");
    private static final AttributeKey<String> MAILBOX = AttributeKey.valueOf("mailbox");
    private static final SecureRandom RANDOM = new SecureRandom();

    private final MailboxStore store;
    private final WakeBus bus;
    private final Sessions sessions;
    private final ObjectMapper mapper;
    private final ExecutorService exec;

    GatewayHandler(MailboxStore store, WakeBus bus, Sessions sessions, ObjectMapper mapper, ExecutorService exec) {
        this.store = store;
        this.bus = bus;
        this.sessions = sessions;
        this.mapper = mapper;
        this.exec = exec;
    }

    @Override
    public void userEventTriggered(ChannelHandlerContext ctx, Object evt) throws Exception {
        if (evt instanceof WebSocketServerProtocolHandler.HandshakeComplete) {
            byte[] n = new byte[16];
            RANDOM.nextBytes(n);
            String nonce = HexFormat.of().formatHex(n);
            ctx.channel().attr(NONCE).set(nonce);
            ObjectNode o = mapper.createObjectNode();
            o.put("t", "challenge");
            o.put("nonce", nonce);
            send(ctx, o);
        } else if (evt instanceof IdleStateEvent) {
            ctx.close();
        } else {
            super.userEventTriggered(ctx, evt);
        }
    }

    @Override
    protected void channelRead0(ChannelHandlerContext ctx, TextWebSocketFrame frame) {
        String text = frame.text();
        exec.execute(() -> {
            try {
                handle(ctx, text);
            } catch (IllegalArgumentException e) {
                fail(ctx, "bad_request: " + e.getMessage(), ctx.channel().attr(MAILBOX).get() == null);
            } catch (Exception e) {
                LOG.warn("gateway error", e);
                fail(ctx, "internal", false);
            }
        });
    }

    private void handle(ChannelHandlerContext ctx, String text) {
        JsonNode n;
        try {
            n = mapper.readTree(text);
        } catch (IOException e) {
            throw new IllegalArgumentException("invalid json");
        }
        String t = n.path("t").asText();
        String mailbox = ctx.channel().attr(MAILBOX).get();
        if (mailbox == null) {
            if (!"auth".equals(t)) {
                fail(ctx, "auth_required", true);
                return;
            }
            onAuth(ctx, n);
            return;
        }
        switch (t) {
            case "send" -> onSend(ctx, n);
            case "sync" -> onSync(ctx, mailbox, n);
            case "ack" -> onAck(ctx, mailbox, n);
            case "ping" -> {
                ObjectNode o = mapper.createObjectNode();
                o.put("t", "pong");
                send(ctx, o);
            }
            default -> throw new IllegalArgumentException("unknown message type");
        }
    }

    private void onAuth(ChannelHandlerContext ctx, JsonNode n) {
        String nonce = ctx.channel().attr(NONCE).get();
        if (nonce == null) {
            fail(ctx, "no_challenge", true);
            return;
        }
        byte[] raw = Base64.getDecoder().decode(n.path("key").asText());
        PublicKey key = DeviceAuth.publicKeyFromRaw(raw);
        byte[] sig = Base64.getDecoder().decode(n.path("sig").asText());
        if (!DeviceAuth.verify(key, "NOMAD-WS-AUTH\n" + nonce, sig)) {
            fail(ctx, "auth_failed", true);
            return;
        }
        String mailbox = Ids.mailboxIdFor(raw);
        ctx.channel().attr(MAILBOX).set(mailbox);
        sessions.add(mailbox, ctx.channel());
        ctx.channel().closeFuture().addListener(f -> sessions.remove(mailbox, ctx.channel()));
        ObjectNode o = mapper.createObjectNode();
        o.put("t", "auth_ok");
        o.put("mailbox", mailbox);
        o.put("epoch", store.epoch());
        send(ctx, o);
    }

    private void onSend(ChannelHandlerContext ctx, JsonNode n) {
        Envelope env = new Envelope(
                n.path("v").asInt(),
                UUID.fromString(n.path("id").asText()),
                n.path("mailbox").asText(),
                Base64.getDecoder().decode(n.path("ct").asText()),
                n.path("exp").asLong());
        AppendResult r = store.append(env);
        if (!r.duplicate()) {
            bus.publish(env.mailboxId());
        }
        ObjectNode o = mapper.createObjectNode();
        o.put("t", "ack");
        o.put("id", env.envelopeId().toString());
        o.put("seq", r.seq());
        o.put("dup", r.duplicate());
        send(ctx, o);
    }

    private void onSync(ChannelHandlerContext ctx, String mailbox, JsonNode n) {
        long after = n.path("after").asLong(0);
        int limit = Math.max(1, Math.min(n.path("limit").asInt(100), 200));
        ReadResult rr = store.read(mailbox, after, limit);
        ObjectNode o = mapper.createObjectNode();
        o.put("t", "msgs");
        o.put("epoch", rr.epoch());
        o.put("more", rr.items().size() == limit);
        ArrayNode items = o.putArray("items");
        for (StoredEnvelope s : rr.items()) {
            ObjectNode it = items.addObject();
            it.put("seq", s.seq());
            it.put("id", s.envelope().envelopeId().toString());
            it.put("v", s.envelope().version());
            it.put("ct", Base64.getEncoder().encodeToString(s.envelope().ciphertext()));
            it.put("exp", s.envelope().expiryDay());
        }
        send(ctx, o);
    }

    private void onAck(ChannelHandlerContext ctx, String mailbox, JsonNode n) {
        ObjectNode o = mapper.createObjectNode();
        o.put("t", "acked");
        o.put("deleted", store.deleteUpTo(mailbox, n.path("upTo").asLong()));
        send(ctx, o);
    }

    private void send(ChannelHandlerContext ctx, ObjectNode o) {
        ctx.channel().writeAndFlush(new TextWebSocketFrame(o.toString()));
    }

    private void fail(ChannelHandlerContext ctx, String msg, boolean close) {
        ObjectNode o = mapper.createObjectNode();
        o.put("t", "error");
        o.put("msg", msg);
        var f = ctx.channel().writeAndFlush(new TextWebSocketFrame(o.toString()));
        if (close) {
            f.addListener(ChannelFutureListener.CLOSE);
        }
    }

    @Override
    public void exceptionCaught(ChannelHandlerContext ctx, Throwable cause) {
        LOG.debug("channel error", cause);
        ctx.close();
    }
}

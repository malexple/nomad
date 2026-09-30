package org.nomad.sim;

import java.net.http.HttpClient;
import java.time.Duration;
import java.util.ArrayList;
import java.util.HashMap;
import java.util.List;
import java.util.Map;
import java.util.concurrent.CompletableFuture;
import java.util.concurrent.Executors;
import java.util.concurrent.ScheduledExecutorService;
import java.util.concurrent.TimeUnit;

/**
 * Emulates N users spread over one or more gateway nodes; every user sends M messages to the others,
 * then the tool checks exactly-once delivery, prints latency percentiles and a per-user table.
 *
 * args: --urls ws://h1:8090/v1/ws,ws://h2:8091/v1/ws  --users 5  --messages 20  --timeout 30  --delay-ms 0
 *       --poll-sec 5  --encrypt false
 * --poll-sec 0 disables the periodic sync (then only wake signals deliver: use it to test the bus).
 * --encrypt true: X3DH + Double Ratchet between all users (the node sees only ciphertext).
 * Exit code 0 = everything delivered exactly once (and decrypted), 1 = something is missing or duplicated.
 */
public final class Main {
    public static void main(String[] args) throws Exception {
        Map<String, String> a = parse(args);
        String[] urls = a.getOrDefault("urls", "ws://localhost:8090/v1/ws").split(",");
        int users = Integer.parseInt(a.getOrDefault("users", "3"));
        int messages = Integer.parseInt(a.getOrDefault("messages", "10"));
        int timeoutSec = Integer.parseInt(a.getOrDefault("timeout", "30"));
        long delayMs = Long.parseLong(a.getOrDefault("delay-ms", "0"));
        int pollSec = Integer.parseInt(a.getOrDefault("poll-sec", "5"));
        boolean encrypt = Boolean.parseBoolean(a.getOrDefault("encrypt", "false"));

        Stats stats = new Stats();
        HttpClient http = HttpClient.newHttpClient();
        List<SimUser> list = new ArrayList<>();
        for (int i = 0; i < users; i++) {
            list.add(new SimUser("u" + i, urls[i % urls.length].trim(), stats, encrypt));
        }
        list.forEach(u -> u.connect(http));
        CompletableFuture.allOf(list.stream().map(u -> u.ready).toArray(CompletableFuture[]::new))
                .get(15, TimeUnit.SECONDS);
        CompletableFuture.allOf(list.stream().map(u -> u.published).toArray(CompletableFuture[]::new))
                .get(15, TimeUnit.SECONDS);
        System.out.printf("connected %d users to %d node(s), poll-sec=%d, encrypt=%s%n",
                users, urls.length, pollSec, encrypt);

        ScheduledExecutorService timer = Executors.newSingleThreadScheduledExecutor(r -> {
            Thread t = new Thread(r, "sim-timer");
            t.setDaemon(true);
            return t;
        });
        if (pollSec > 0) {
            timer.scheduleAtFixedRate(() -> list.forEach(SimUser::syncNow), pollSec, pollSec, TimeUnit.SECONDS);
        }
        timer.scheduleAtFixedRate(() -> list.forEach(SimUser::ping), 30, 30, TimeUnit.SECONDS);

        Map<String, Integer> expectedIn = new HashMap<>();
        long started = System.nanoTime();
        for (int m = 0; m < messages; m++) {
            for (int i = 0; i < users; i++) {
                SimUser from = list.get(i);
                SimUser to = users > 1 ? list.get((i + 1 + (m % (users - 1))) % users) : from;
                expectedIn.merge(to.name, 1, Integer::sum);
                try {
                    from.sendMessage(to,
                            "sim|" + from.name + "|" + to.name + "|" + m + "|" + System.currentTimeMillis());
                } catch (Exception e) {
                    stats.errors.incrementAndGet();
                    System.err.println(from.name + ": send failed: " + e);
                }
            }
            if (delayMs > 0) {
                Thread.sleep(delayMs);
            }
        }

        int expected = users * messages;
        long deadline = System.nanoTime() + Duration.ofSeconds(timeoutSec).toNanos();
        long lastPrint = System.nanoTime();
        while (stats.delivered.get() < expected && System.nanoTime() < deadline) {
            Thread.sleep(100);
            if (System.nanoTime() - lastPrint > 2_000_000_000L) {
                lastPrint = System.nanoTime();
                System.out.printf("  t=%.1fs delivered=%d/%d sendAcks=%d%n",
                        (System.nanoTime() - started) / 1e9, stats.delivered.get(), expected, stats.sendAcks.get());
            }
        }
        double seconds = (System.nanoTime() - started) / 1e9;

        System.out.printf("expected=%d delivered=%d duplicates=%d sendAcks=%d errors=%d foreign=%d undecryptable=%d%n",
                expected, stats.delivered.get(), stats.duplicates.get(), stats.sendAcks.get(),
                stats.errors.get(), stats.foreign.get(), stats.undecryptable.get());
        System.out.printf("latency ms: p50=%d p95=%d max=%d  total=%.2fs%n",
                stats.percentile(0.50), stats.percentile(0.95), stats.percentile(1.0), seconds);
        System.out.println("per user (name, node, expected incoming, got, connection state):");
        for (SimUser u : list) {
            System.out.printf("  %s %s in=%d got=%d %s%n", u.name, u.url,
                    expectedIn.getOrDefault(u.name, 0), u.received.get(),
                    u.closedInfo == null ? "open" : u.closedInfo);
        }
        list.forEach(SimUser::close);
        Thread.sleep(300);
        boolean ok = stats.delivered.get() == expected && stats.duplicates.get() == 0
                && stats.errors.get() == 0 && stats.undecryptable.get() == 0;
        System.out.println(ok ? "RESULT: OK" : "RESULT: FAIL");
        System.exit(ok ? 0 : 1);
    }

    private static Map<String, String> parse(String[] args) {
        Map<String, String> m = new HashMap<>();
        for (int i = 0; i + 1 < args.length; i += 2) {
            m.put(args[i].replaceFirst("^--", ""), args[i + 1]);
        }
        return m;
    }
}

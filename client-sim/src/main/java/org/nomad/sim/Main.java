package org.nomad.sim;

import java.net.http.HttpClient;
import java.time.Duration;
import java.util.ArrayList;
import java.util.HashMap;
import java.util.List;
import java.util.Map;
import java.util.concurrent.CompletableFuture;
import java.util.concurrent.TimeUnit;

/**
 * Emulates N users spread over one or more gateway nodes; every user sends M messages to the others,
 * then the tool checks exactly-once delivery and prints latency percentiles.
 *
 * args: --urls ws://h1:8090/v1/ws,ws://h2:8091/v1/ws  --users 5  --messages 20  --timeout 30  --delay-ms 0
 * Exit code 0 = everything delivered exactly once, 1 = something is missing or duplicated.
 */
public final class Main {
    public static void main(String[] args) throws Exception {
        Map<String, String> a = parse(args);
        String[] urls = a.getOrDefault("urls", "ws://localhost:8090/v1/ws").split(",");
        int users = Integer.parseInt(a.getOrDefault("users", "3"));
        int messages = Integer.parseInt(a.getOrDefault("messages", "10"));
        int timeoutSec = Integer.parseInt(a.getOrDefault("timeout", "30"));
        long delayMs = Long.parseLong(a.getOrDefault("delay-ms", "0"));

        Stats stats = new Stats();
        HttpClient http = HttpClient.newHttpClient();
        List<SimUser> list = new ArrayList<>();
        for (int i = 0; i < users; i++) {
            list.add(new SimUser("u" + i, urls[i % urls.length].trim(), stats));
        }
        list.forEach(u -> u.connect(http));
        CompletableFuture.allOf(list.stream().map(u -> u.ready).toArray(CompletableFuture[]::new))
                .get(15, TimeUnit.SECONDS);
        System.out.printf("connected %d users to %d node(s)%n", users, urls.length);

        long started = System.nanoTime();
        for (int m = 0; m < messages; m++) {
            for (int i = 0; i < users; i++) {
                SimUser from = list.get(i);
                SimUser to = users > 1 ? list.get((i + 1 + (m % (users - 1))) % users) : from;
                from.sendEnvelope(to.mailboxId(),
                        "sim|" + from.name + "|" + to.name + "|" + m + "|" + System.currentTimeMillis());
            }
            if (delayMs > 0) {
                Thread.sleep(delayMs);
            }
        }

        int expected = users * messages;
        long deadline = System.nanoTime() + Duration.ofSeconds(timeoutSec).toNanos();
        while (stats.delivered.get() < expected && System.nanoTime() < deadline) {
            Thread.sleep(100);
        }
        double seconds = (System.nanoTime() - started) / 1e9;

        System.out.printf("expected=%d delivered=%d duplicates=%d sendAcks=%d errors=%d foreign=%d%n",
                expected, stats.delivered.get(), stats.duplicates.get(), stats.sendAcks.get(),
                stats.errors.get(), stats.foreign.get());
        System.out.printf("latency ms: p50=%d p95=%d max=%d  total=%.2fs%n",
                stats.percentile(0.50), stats.percentile(0.95), stats.percentile(1.0), seconds);
        list.forEach(SimUser::close);
        Thread.sleep(300);
        boolean ok = stats.delivered.get() == expected && stats.duplicates.get() == 0 && stats.errors.get() == 0;
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

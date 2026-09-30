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
import org.nomad.client.ClientState;
import org.nomad.crypto.StateVault;

/**
 * Emulates N users spread over one or more gateway nodes; every user sends M direct messages to the others,
 * optionally one group chat is created and each member sends K group messages. The tool checks exactly-once
 * delivery, prints latency percentiles and a per-user table.
 *
 * args: --urls ws://h1:8090/v1/ws,ws://h2:8091/v1/ws  --users 5  --messages 20  --timeout 30  --delay-ms 0
 *       --poll-sec 5  --encrypt false  --group-size 0  --group-messages 5  --restart-user -1
 * --poll-sec 0 disables the periodic sync (then only wake signals deliver: use it to test the bus).
 * --encrypt true: X3DH + Double Ratchet between all users (the node sees only ciphertext).
 * --group-size N (needs --encrypt true): the first N users form a group (u0 is the admin) with Sender Keys.
 * --restart-user I (needs --encrypt true): after the first round user I is "killed" (state saved, connection closed),
 *   the others send a second round while it is offline, then it restarts from the saved state and sends its second round.
 *   Nothing may be lost and the restarted user must not need a new prekey bundle for peers it already knew.
 * Load test: a large --messages with --delay-ms 0 exceeds the node's per-device limits; the simulator backs off
 * and repeats, so everything must still arrive (rateLimited shows how often the node said no).
 * Exit code 0 = everything delivered exactly once and decrypted, 1 = something is missing or duplicated.
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
        int groupSize = Integer.parseInt(a.getOrDefault("group-size", "0"));
        int groupMessages = Integer.parseInt(a.getOrDefault("group-messages", "5"));
        int restartUser = Integer.parseInt(a.getOrDefault("restart-user", "-1"));
        if (groupSize > 0 && (!encrypt || groupSize < 2 || groupSize > users)) {
            throw new IllegalArgumentException("--group-size needs --encrypt true and 2 <= size <= users");
        }
        if (restartUser >= users || (restartUser >= 0 && (!encrypt || users < 2))) {
            throw new IllegalArgumentException("--restart-user needs --encrypt true and a valid user index");
        }

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
        System.out.printf("connected %d users to %d node(s), poll-sec=%d, encrypt=%s, group-size=%d, restart-user=%d%n",
                users, urls.length, pollSec, encrypt, groupSize, restartUser);

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
        List<CompletableFuture<Void>> pending = new ArrayList<>();
        long started = System.nanoTime();
        int expected = directRound(list, list, messages, 0, delayMs, pending, expectedIn);
        String gid = null;
        if (groupSize > 0) {
            List<SimUser> members = list.subList(0, groupSize);
            gid = members.get(0).createGroup(members.subList(1, groupSize)).get(30, TimeUnit.SECONDS);
            expected += groupRound(list, list, gid, groupSize, groupMessages, 0, delayMs, pending, expectedIn);
        }
        CompletableFuture.allOf(pending.toArray(CompletableFuture[]::new)).get(60, TimeUnit.SECONDS);

        int restartedFetches = -1;
        if (restartUser >= 0) {
            waitDelivered(stats, expected, timeoutSec);
            System.out.printf("round 1 done (%d delivered), restarting u%d%n", stats.delivered.get(), restartUser);
            byte[] masterKey = StateVault.newKey();
            SimUser old = list.get(restartUser);
            byte[] saved = old.snapshot(masterKey);
            old.close();
            Thread.sleep(500);

            List<SimUser> others = new ArrayList<>(list);
            others.remove(restartUser);
            pending.clear();
            expected += directRound(list, others, messages, messages, delayMs, pending, expectedIn);
            if (groupSize > 0) {
                expected += groupRound(list, others, gid, groupSize, groupMessages, groupMessages, delayMs, pending,
                        expectedIn);
            }
            CompletableFuture.allOf(pending.toArray(CompletableFuture[]::new)).get(60, TimeUnit.SECONDS);
            Thread.sleep(1500);

            SimUser restarted = new SimUser(old.name, old.url, stats, ClientState.open(masterKey, saved));
            list.set(restartUser, restarted);
            restarted.connect(http);
            restarted.ready.get(15, TimeUnit.SECONDS);
            System.out.printf("u%d is back (uid unchanged: %s)%n", restartUser, restarted.uid().equals(old.uid()));

            pending.clear();
            List<SimUser> only = List.of(restarted);
            expected += directRound(list, only, messages, messages, delayMs, pending, expectedIn);
            if (groupSize > 0) {
                expected += groupRound(list, only, gid, groupSize, groupMessages, groupMessages, delayMs, pending,
                        expectedIn);
            }
            CompletableFuture.allOf(pending.toArray(CompletableFuture[]::new)).get(60, TimeUnit.SECONDS);
            restartedFetches = restarted.bundleFetches.get();
        }

        waitDelivered(stats, expected, timeoutSec);
        double seconds = (System.nanoTime() - started) / 1e9;
        stats.undecryptable.set(list.stream().mapToInt(SimUser::heldCount).sum());
        int unacked = list.stream().mapToInt(SimUser::unacknowledgedSends).sum();

        System.out.printf("expected=%d delivered=%d duplicates=%d sendAcks=%d errors=%d foreign=%d "
                        + "stillHeldUndecryptable=%d rateLimited=%d unackedSends=%d%n",
                expected, stats.delivered.get(), stats.duplicates.get(), stats.sendAcks.get(),
                stats.errors.get(), stats.foreign.get(), stats.undecryptable.get(),
                stats.rateLimited.get(), unacked);
        System.out.printf("latency ms: p50=%d p95=%d max=%d  total=%.2fs%n",
                stats.percentile(0.50), stats.percentile(0.95), stats.percentile(1.0), seconds);
        System.out.println("per user (name, node, expected incoming, got since (re)start, connection state):");
        for (SimUser u : list) {
            System.out.printf("  %s %s in=%d got=%d %s%n", u.name, u.url,
                    expectedIn.getOrDefault(u.name, 0), u.received.get(),
                    u.closedInfo == null ? "open" : u.closedInfo);
        }
        boolean knewEveryone = messages >= users - 1;
        boolean restartOk = restartedFetches <= 0 || !knewEveryone;
        if (restartUser >= 0) {
            System.out.printf("restarted user fetched %d prekey bundle(s) after the restart (expected 0)%n",
                    restartedFetches);
        }
        list.forEach(SimUser::close);
        Thread.sleep(300);
        boolean ok = stats.delivered.get() == expected && stats.duplicates.get() == 0
                && stats.errors.get() == 0 && stats.undecryptable.get() == 0 && restartOk;
        System.out.println(ok ? "RESULT: OK" : "RESULT: FAIL");
        System.exit(ok ? 0 : 1);
    }

    /** Every sender in {@code senders} sends {@code messages} direct messages; returns the number of deliveries expected. */
    private static int directRound(List<SimUser> list, List<SimUser> senders, int messages, int indexOffset,
            long delayMs, List<CompletableFuture<Void>> pending, Map<String, Integer> expectedIn)
            throws InterruptedException {
        int users = list.size();
        int expected = 0;
        for (int m = 0; m < messages; m++) {
            for (SimUser from : senders) {
                int i = list.indexOf(from);
                SimUser to = users > 1 ? list.get((i + 1 + (m % (users - 1))) % users) : from;
                expectedIn.merge(to.name, 1, Integer::sum);
                pending.add(from.sendChat(to, m + indexOffset));
                expected++;
            }
            if (delayMs > 0) {
                Thread.sleep(delayMs);
            }
        }
        return expected;
    }

    /** Every group member among {@code senders} sends {@code groupMessages} group messages to the others. */
    private static int groupRound(List<SimUser> list, List<SimUser> senders, String gid, int groupSize,
            int groupMessages, int indexOffset, long delayMs, List<CompletableFuture<Void>> pending,
            Map<String, Integer> expectedIn) throws InterruptedException {
        List<SimUser> members = list.subList(0, groupSize);
        int expected = 0;
        for (int m = 0; m < groupMessages; m++) {
            for (SimUser u : members) {
                if (!senders.contains(u)) {
                    continue;
                }
                pending.add(u.sendGroup(gid, m + indexOffset));
                for (SimUser other : members) {
                    if (other != u) {
                        expectedIn.merge(other.name, 1, Integer::sum);
                        expected++;
                    }
                }
            }
            if (delayMs > 0) {
                Thread.sleep(delayMs);
            }
        }
        return expected;
    }

    private static void waitDelivered(Stats stats, int expected, int timeoutSec) throws InterruptedException {
        long deadline = System.nanoTime() + Duration.ofSeconds(timeoutSec).toNanos();
        long lastPrint = System.nanoTime();
        long started = System.nanoTime();
        while (stats.delivered.get() < expected && System.nanoTime() < deadline) {
            Thread.sleep(100);
            if (System.nanoTime() - lastPrint > 2_000_000_000L) {
                lastPrint = System.nanoTime();
                System.out.printf("  t=%.1fs delivered=%d/%d sendAcks=%d rateLimited=%d%n",
                        (System.nanoTime() - started) / 1e9, stats.delivered.get(), expected,
                        stats.sendAcks.get(), stats.rateLimited.get());
            }
        }
    }

    private static Map<String, String> parse(String[] args) {
        Map<String, String> m = new HashMap<>();
        for (int i = 0; i + 1 < args.length; i += 2) {
            m.put(args[i].replaceFirst("^--", ""), args[i + 1]);
        }
        return m;
    }
}

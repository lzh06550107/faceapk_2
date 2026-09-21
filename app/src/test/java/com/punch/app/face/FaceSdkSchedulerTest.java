package com.punch.app.face;

import org.junit.Test;

import java.util.ArrayList;
import java.util.Collections;
import java.util.List;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.AtomicInteger;

import static org.junit.Assert.assertEquals;
import static org.junit.Assert.assertTrue;

public class FaceSdkSchedulerTest {

    @Test
    public void neverExecutesNativeTasksConcurrently() throws Exception {
        FaceSdkScheduler scheduler = new FaceSdkScheduler(8, 250L, "test-sdk-serial");
        ExecutorService callers = Executors.newFixedThreadPool(6);
        AtomicInteger active = new AtomicInteger();
        AtomicInteger maxActive = new AtomicInteger();
        CountDownLatch done = new CountDownLatch(12);
        try {
            for (int i = 0; i < 12; i++) {
                final int index = i;
                callers.execute(() -> {
                    if ((index & 1) == 0) {
                        scheduler.callRealtime("r" + index, () -> recordActive(active, maxActive));
                    } else {
                        scheduler.callBackground("b" + index, () -> recordActive(active, maxActive));
                    }
                    done.countDown();
                });
            }
            assertTrue(done.await(3, TimeUnit.SECONDS));
            assertEquals(1, maxActive.get());
        } finally {
            callers.shutdownNow();
            scheduler.shutdownForTest();
        }
    }

    @Test
    public void realtimeRunsBeforeNextQueuedBackgroundTask() throws Exception {
        FaceSdkScheduler scheduler = new FaceSdkScheduler(8, 1000L, "test-sdk-priority");
        ExecutorService callers = Executors.newFixedThreadPool(3);
        CountDownLatch firstStarted = new CountDownLatch(1);
        CountDownLatch releaseFirst = new CountDownLatch(1);
        CountDownLatch finished = new CountDownLatch(3);
        List<String> order = Collections.synchronizedList(new ArrayList<>());
        try {
            callers.execute(() -> {
                scheduler.callBackground("background-1", () -> {
                    order.add("background-1");
                    firstStarted.countDown();
                    await(releaseFirst);
                    return null;
                });
                finished.countDown();
            });
            assertTrue(firstStarted.await(1, TimeUnit.SECONDS));

            callers.execute(() -> {
                scheduler.callBackground("background-2", () -> {
                    order.add("background-2");
                    return null;
                });
                finished.countDown();
            });
            callers.execute(() -> {
                scheduler.callRealtime("realtime", () -> {
                    order.add("realtime");
                    return null;
                });
                finished.countDown();
            });

            Thread.sleep(40L);
            releaseFirst.countDown();
            assertTrue(finished.await(2, TimeUnit.SECONDS));
            assertEquals("background-1", order.get(0));
            assertEquals("realtime", order.get(1));
            assertEquals("background-2", order.get(2));
        } finally {
            releaseFirst.countDown();
            callers.shutdownNow();
            scheduler.shutdownForTest();
        }
    }

    @Test
    public void preservesFifoWithinBackgroundPriority() throws Exception {
        FaceSdkScheduler scheduler = new FaceSdkScheduler(8, 1000L, "test-sdk-fifo");
        List<String> order = Collections.synchronizedList(new ArrayList<>());
        ExecutorService caller = Executors.newSingleThreadExecutor();
        try {
            caller.submit(() -> scheduler.callBackground("b1", () -> { order.add("b1"); return null; })).get();
            caller.submit(() -> scheduler.callBackground("b2", () -> { order.add("b2"); return null; })).get();
            caller.submit(() -> scheduler.callBackground("b3", () -> { order.add("b3"); return null; })).get();
            assertEquals(java.util.Arrays.asList("b1", "b2", "b3"), order);
        } finally {
            caller.shutdownNow();
            scheduler.shutdownForTest();
        }
    }

    @Test
    public void boundedRealtimeBurstAllowsWaitingBackgroundProgress() throws Exception {
        FaceSdkScheduler scheduler = new FaceSdkScheduler(2, 0L, "test-sdk-aging");
        ExecutorService callers = Executors.newFixedThreadPool(5);
        CountDownLatch blockerStarted = new CountDownLatch(1);
        CountDownLatch releaseBlocker = new CountDownLatch(1);
        CountDownLatch finished = new CountDownLatch(5);
        List<String> order = Collections.synchronizedList(new ArrayList<>());
        try {
            callers.execute(() -> {
                scheduler.callRealtime("blocker", () -> {
                    order.add("blocker");
                    blockerStarted.countDown();
                    await(releaseBlocker);
                    return null;
                });
                finished.countDown();
            });
            assertTrue(blockerStarted.await(1, TimeUnit.SECONDS));

            callers.execute(() -> { scheduler.callBackground("background", () -> { order.add("background"); return null; }); finished.countDown(); });
            callers.execute(() -> { scheduler.callRealtime("r1", () -> { order.add("r1"); return null; }); finished.countDown(); });
            callers.execute(() -> { scheduler.callRealtime("r2", () -> { order.add("r2"); return null; }); finished.countDown(); });
            callers.execute(() -> { scheduler.callRealtime("r3", () -> { order.add("r3"); return null; }); finished.countDown(); });

            Thread.sleep(40L);
            releaseBlocker.countDown();
            assertTrue(finished.await(2, TimeUnit.SECONDS));
            int backgroundIndex = order.indexOf("background");
            assertTrue("background should run before all queued realtime work is exhausted: " + order,
                    backgroundIndex > 0 && backgroundIndex < order.size() - 1);
        } finally {
            releaseBlocker.countDown();
            callers.shutdownNow();
            scheduler.shutdownForTest();
        }
    }

    @Test
    public void exposesSchedulerQueueWaitToRunningOperation() throws Exception {
        FaceSdkScheduler scheduler = new FaceSdkScheduler(8, 1000L, "test-sdk-wait");
        ExecutorService callers = Executors.newFixedThreadPool(2);
        CountDownLatch blockerStarted = new CountDownLatch(1);
        CountDownLatch releaseBlocker = new CountDownLatch(1);
        try {
            callers.submit(() -> scheduler.callBackground("blocker", () -> {
                blockerStarted.countDown();
                await(releaseBlocker);
                return null;
            }));
            assertTrue(blockerStarted.await(1, TimeUnit.SECONDS));

            java.util.concurrent.Future<Long> waited = callers.submit(() ->
                    scheduler.callRealtime("waited", FaceSdkScheduler::currentQueueWaitMs));
            Thread.sleep(40L);
            releaseBlocker.countDown();

            assertTrue("queued task should observe scheduler wait", waited.get() >= 20L);
        } finally {
            releaseBlocker.countDown();
            callers.shutdownNow();
            scheduler.shutdownForTest();
        }
    }

    private static Void recordActive(AtomicInteger active, AtomicInteger maxActive) {
        int now = active.incrementAndGet();
        maxActive.accumulateAndGet(now, Math::max);
        try {
            Thread.sleep(10L);
        } catch (InterruptedException e) {
            Thread.currentThread().interrupt();
        } finally {
            active.decrementAndGet();
        }
        return null;
    }

    private static void await(CountDownLatch latch) {
        try {
            if (!latch.await(2, TimeUnit.SECONDS)) {
                throw new AssertionError("Timed out waiting for latch");
            }
        } catch (InterruptedException e) {
            Thread.currentThread().interrupt();
            throw new AssertionError(e);
        }
    }
}

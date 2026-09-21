package com.punch.app.service;

import org.junit.Test;

import java.util.concurrent.CountDownLatch;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.AtomicBoolean;
import java.util.concurrent.atomic.AtomicInteger;

import static org.junit.Assert.assertEquals;
import static org.junit.Assert.assertTrue;

public class PlatformEventExecutorTest {
    @Test
    public void submitReturnsWithoutWaitingForLongEventBody() throws Exception {
        PlatformEventExecutor executor = new PlatformEventExecutor("event-test-fast-return");
        CountDownLatch bodyStarted = new CountDownLatch(1);
        CountDownLatch releaseBody = new CountDownLatch(1);
        try {
            long started = System.nanoTime();
            assertEquals(PlatformEventExecutor.SubmitResult.STARTED,
                    executor.submit("ABC", () -> {
                        bodyStarted.countDown();
                        await(releaseBody);
                        return () -> true;
                    }));
            long elapsedMs = TimeUnit.NANOSECONDS.toMillis(System.nanoTime() - started);
            assertTrue("submit should be non-blocking, elapsed=" + elapsedMs, elapsedMs < 100L);
            assertTrue(bodyStarted.await(1, TimeUnit.SECONDS));
        } finally {
            releaseBody.countDown();
            executor.shutdownForTest();
        }
    }

    @Test
    public void duplicateRunningCursorDoesNotRunBodyTwice() throws Exception {
        PlatformEventExecutor executor = new PlatformEventExecutor("event-test-dedupe");
        CountDownLatch bodyStarted = new CountDownLatch(1);
        CountDownLatch releaseBody = new CountDownLatch(1);
        AtomicInteger bodies = new AtomicInteger();
        try {
            executor.submit("ABC", () -> {
                bodies.incrementAndGet();
                bodyStarted.countDown();
                await(releaseBody);
                return () -> true;
            });
            assertTrue(bodyStarted.await(1, TimeUnit.SECONDS));
            assertEquals(PlatformEventExecutor.SubmitResult.IGNORED_RUNNING,
                    executor.submit("ABC", () -> {
                        bodies.incrementAndGet();
                        return () -> true;
                    }));
            assertEquals(1, bodies.get());
        } finally {
            releaseBody.countDown();
            executor.shutdownForTest();
        }
    }

    @Test
    public void ackFailureRetriesAckWithoutRerunningBody() throws Exception {
        PlatformEventExecutor executor = new PlatformEventExecutor("event-test-ack-retry");
        AtomicInteger bodies = new AtomicInteger();
        AtomicInteger acks = new AtomicInteger();
        CountDownLatch firstAck = new CountDownLatch(1);
        CountDownLatch secondAck = new CountDownLatch(1);
        AtomicBoolean allowAck = new AtomicBoolean(false);
        try {
            executor.submit("ABC", () -> {
                bodies.incrementAndGet();
                return () -> {
                    int count = acks.incrementAndGet();
                    if (count == 1) firstAck.countDown();
                    if (count == 2) secondAck.countDown();
                    return allowAck.get();
                };
            });
            assertTrue(firstAck.await(1, TimeUnit.SECONDS));
            assertEquals(1, bodies.get());

            allowAck.set(true);
            assertEquals(PlatformEventExecutor.SubmitResult.ACK_RETRY_QUEUED,
                    executor.submit("ABC", () -> {
                        bodies.incrementAndGet();
                        return () -> true;
                    }));
            assertTrue(secondAck.await(1, TimeUnit.SECONDS));
            assertEquals(1, bodies.get());
            assertEquals(2, acks.get());
        } finally {
            executor.shutdownForTest();
        }
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

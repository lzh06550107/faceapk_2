package com.punch.app.utils;

/**
 * OTA 失败重试的纯策略：前几次快速退避，之后固定每 3 小时继续尝试。
 * 不设置最大重试次数；是否属于可重试错误由 UpdateManager 决定。
 */
public final class UpdateRetryPolicy {
    private static final long RETRY_1 = 60_000L;
    private static final long RETRY_2 = 5 * 60_000L;
    private static final long RETRY_3 = 15 * 60_000L;
    private static final long RETRY_4 = 30 * 60_000L;
    private static final long RETRY_5 = 60 * 60_000L;
    private static final long RETRY_LATER = 3 * 60 * 60_000L;

    private UpdateRetryPolicy() {
    }

    public static long delayMsForFailureCount(int failureCount) {
        if (failureCount <= 1) {
            return RETRY_1;
        }
        if (failureCount == 2) {
            return RETRY_2;
        }
        if (failureCount == 3) {
            return RETRY_3;
        }
        if (failureCount == 4) {
            return RETRY_4;
        }
        if (failureCount == 5) {
            return RETRY_5;
        }
        return RETRY_LATER;
    }

    public static int nextFailureCount(int previousFailureCount, boolean sameGeneration) {
        if (!sameGeneration) {
            return 1;
        }
        if (previousFailureCount < 1) {
            return 1;
        }
        return previousFailureCount == Integer.MAX_VALUE
                ? Integer.MAX_VALUE
                : previousFailureCount + 1;
    }
}

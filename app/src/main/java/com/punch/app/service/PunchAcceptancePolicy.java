package com.punch.app.service;

/** Recovery policy for punch acceptance attempts that did not yield a final boolean decision. */
public final class PunchAcceptancePolicy {
    private PunchAcceptancePolicy() {
    }

    /**
     * HTTP/transport status alone never decides whether a punch is accepted.
     *
     * <p>The caller may finish ACCEPTING only after parsing a valid
     * {@code is_over_capacity=true/false}. Any response code reaching this policy therefore
     * remains recoverable and must keep the durable ACCEPTING intent.</p>
     */
    public static boolean shouldRetryLater(int responseCode) {
        return true;
    }
}

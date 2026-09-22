package com.punch.app.service;

/** Recovery policy for the server-side punch acceptance step. */
public final class PunchAcceptancePolicy {
    private PunchAcceptancePolicy() {
    }

    /**
     * Keep the durable ACCEPTING intent whenever the response is not a clearly definitive
     * client/business rejection. Transport, authentication, throttling and server failures may
     * have happened after the server committed, so they must be retried with the same
     * client_record_id rather than creating a new punch.
     */
    public static boolean shouldRetryLater(int responseCode) {
        return responseCode != 400
                && responseCode != 403
                && responseCode != 404
                && responseCode != 422;
    }
}

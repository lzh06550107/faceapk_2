package com.punch.app.utils;

import java.io.IOException;
import java.net.DatagramPacket;
import java.net.DatagramSocket;
import java.net.InetAddress;

public final class NtpProbeClient {
    private static final int NTP_PORT = 123;
    private static final int MIN_TIMEOUT_MS = 500;
    private static final int MAX_TIMEOUT_MS = 10_000;

    private NtpProbeClient() {
    }

    public static ProbeResult probe(String rawHost, int timeoutMs) {
        String host = SystemNtpPolicy.normalizeHost(rawHost);
        if (!SystemNtpPolicy.isValidHost(host)) {
            return ProbeResult.failure("NTP server host is invalid");
        }
        int boundedTimeoutMs = normalizeTimeoutMs(timeoutMs);
        try (DatagramSocket socket = new DatagramSocket()) {
            socket.setSoTimeout(boundedTimeoutMs);
            InetAddress address = InetAddress.getByName(host);
            long sentAtMillis = System.currentTimeMillis();
            byte[] requestBytes = NtpPacket.newClientRequest(sentAtMillis);
            DatagramPacket request = new DatagramPacket(
                    requestBytes,
                    requestBytes.length,
                    address,
                    NTP_PORT
            );
            socket.send(request);

            byte[] responseBytes = new byte[512];
            DatagramPacket response = new DatagramPacket(responseBytes, responseBytes.length);
            socket.receive(response);
            long receivedAtMillis = System.currentTimeMillis();

            NtpPacket.Result parsed = NtpPacket.parseResponse(
                    responseBytes,
                    response.getLength(),
                    sentAtMillis,
                    receivedAtMillis
            );
            return ProbeResult.success(
                    host,
                    parsed.serverTimeMillis,
                    parsed.roundTripMillis,
                    parsed.localClockOffsetMillis,
                    parsed.stratum
            );
        } catch (IOException | IllegalArgumentException e) {
            return ProbeResult.failure(e.getClass().getSimpleName() + ": " + safeMessage(e));
        }
    }

    static int normalizeTimeoutMs(int timeoutMs) {
        if (timeoutMs < MIN_TIMEOUT_MS) {
            return MIN_TIMEOUT_MS;
        }
        if (timeoutMs > MAX_TIMEOUT_MS) {
            return MAX_TIMEOUT_MS;
        }
        return timeoutMs;
    }

    private static String safeMessage(Throwable error) {
        String message = error == null ? null : error.getMessage();
        return message == null || message.trim().isEmpty() ? "unknown error" : message.trim();
    }

    public static final class ProbeResult {
        public final boolean success;
        public final String host;
        public final long serverTimeMillis;
        public final long roundTripMillis;
        public final long localClockOffsetMillis;
        public final int stratum;
        public final String message;

        private ProbeResult(boolean success,
                            String host,
                            long serverTimeMillis,
                            long roundTripMillis,
                            long localClockOffsetMillis,
                            int stratum,
                            String message) {
            this.success = success;
            this.host = host;
            this.serverTimeMillis = serverTimeMillis;
            this.roundTripMillis = roundTripMillis;
            this.localClockOffsetMillis = localClockOffsetMillis;
            this.stratum = stratum;
            this.message = message;
        }

        static ProbeResult success(String host,
                                   long serverTimeMillis,
                                   long roundTripMillis,
                                   long localClockOffsetMillis,
                                   int stratum) {
            return new ProbeResult(
                    true,
                    host,
                    serverTimeMillis,
                    roundTripMillis,
                    localClockOffsetMillis,
                    stratum,
                    "NTP response OK"
            );
        }

        static ProbeResult failure(String message) {
            return new ProbeResult(false, "", 0L, 0L, 0L, 0, message);
        }
    }
}

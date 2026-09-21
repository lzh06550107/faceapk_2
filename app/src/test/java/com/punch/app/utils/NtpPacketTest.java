package com.punch.app.utils;

import org.junit.Test;

import static org.junit.Assert.assertEquals;
import static org.junit.Assert.assertTrue;

public class NtpPacketTest {
    @Test
    public void createsVersionFourClientRequestWithTransmitTimestamp() {
        long now = 1_788_000_000_123L;

        byte[] request = NtpPacket.newClientRequest(now);

        assertEquals(48, request.length);
        assertEquals(3, request[0] & 0x7);
        assertEquals(4, (request[0] >> 3) & 0x7);
        assertEquals(now, NtpPacket.readTimestamp(request, 40));
    }

    @Test
    public void parsesValidServerResponseAndComputesDiagnosticTiming() {
        long sentAt = 1_788_000_000_000L;
        long receivedAt = sentAt + 40L;
        long serverTransmit = sentAt + 15L;
        byte[] response = new byte[48];
        response[0] = (byte) ((4 << 3) | 4);
        response[1] = 2;
        NtpPacket.writeTimestamp(response, 40, serverTransmit);

        NtpPacket.Result result = NtpPacket.parseResponse(
                response,
                response.length,
                sentAt,
                receivedAt
        );

        assertEquals(serverTransmit, result.serverTimeMillis);
        assertEquals(40L, result.roundTripMillis);
        assertEquals(-5L, result.localClockOffsetMillis);
        assertEquals(2, result.stratum);
    }

    @Test(expected = IllegalArgumentException.class)
    public void rejectsShortResponse() {
        NtpPacket.parseResponse(new byte[47], 47, 1_000L, 1_010L);
    }

    @Test(expected = IllegalArgumentException.class)
    public void rejectsClientModeResponse() {
        byte[] response = validResponse();
        response[0] = (byte) ((4 << 3) | 3);
        NtpPacket.parseResponse(response, response.length, 1_000L, 1_010L);
    }

    @Test(expected = IllegalArgumentException.class)
    public void rejectsInvalidStratum() {
        byte[] response = validResponse();
        response[1] = 0;
        NtpPacket.parseResponse(response, response.length, 1_000L, 1_010L);
    }

    @Test(expected = IllegalArgumentException.class)
    public void rejectsZeroTransmitTimestamp() {
        byte[] response = validResponse();
        for (int i = 40; i < 48; i++) {
            response[i] = 0;
        }
        NtpPacket.parseResponse(response, response.length, 1_000L, 1_010L);
    }

    @Test
    public void ntpTimestampRoundTripsAcrossUnixEpochConversion() {
        byte[] packet = new byte[48];
        long expected = 1_788_308_418_022L;

        NtpPacket.writeTimestamp(packet, 40, expected);
        long actual = NtpPacket.readTimestamp(packet, 40);

        assertTrue(Math.abs(actual - expected) <= 1L);
    }

    private static byte[] validResponse() {
        byte[] response = new byte[48];
        response[0] = (byte) ((4 << 3) | 4);
        response[1] = 1;
        NtpPacket.writeTimestamp(response, 40, 1_100L);
        return response;
    }
}

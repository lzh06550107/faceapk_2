package com.punch.app.utils;

public final class NtpPacket {
    private static final int PACKET_SIZE = 48;
    private static final int TRANSMIT_TIMESTAMP_OFFSET = 40;
    private static final long OFFSET_1900_TO_1970_SECONDS = 2_208_988_800L;
    private static final long TWO_POW_32 = 0x1_0000_0000L;

    private NtpPacket() {
    }

    public static byte[] newClientRequest(long nowMillis) {
        byte[] packet = new byte[PACKET_SIZE];
        packet[0] = (byte) ((4 << 3) | 3);
        writeTimestamp(packet, TRANSMIT_TIMESTAMP_OFFSET, nowMillis);
        return packet;
    }

    public static Result parseResponse(byte[] packet,
                                       int packetLength,
                                       long sentAtMillis,
                                       long receivedAtMillis) {
        if (packet == null || packetLength < PACKET_SIZE || packet.length < PACKET_SIZE) {
            throw new IllegalArgumentException("NTP response is shorter than 48 bytes");
        }
        int mode = packet[0] & 0x7;
        if (mode != 4 && mode != 5) {
            throw new IllegalArgumentException("NTP response mode is not server/broadcast: " + mode);
        }
        int stratum = packet[1] & 0xff;
        if (stratum < 1 || stratum > 15) {
            throw new IllegalArgumentException("NTP response stratum is invalid: " + stratum);
        }
        long serverTimeMillis = readTimestamp(packet, TRANSMIT_TIMESTAMP_OFFSET);
        if (serverTimeMillis == 0L) {
            throw new IllegalArgumentException("NTP transmit timestamp is zero");
        }
        long roundTripMillis = Math.max(0L, receivedAtMillis - sentAtMillis);
        long localMidpointMillis = sentAtMillis + roundTripMillis / 2L;
        long localClockOffsetMillis = serverTimeMillis - localMidpointMillis;
        return new Result(serverTimeMillis, roundTripMillis, localClockOffsetMillis, stratum);
    }

    static void writeTimestamp(byte[] buffer, int offset, long unixMillis) {
        if (buffer == null || offset < 0 || offset + 8 > buffer.length) {
            throw new IllegalArgumentException("Timestamp buffer range is invalid");
        }
        if (unixMillis <= 0L) {
            return;
        }
        long unixSeconds = unixMillis / 1000L;
        long millis = unixMillis % 1000L;
        long ntpSeconds = unixSeconds + OFFSET_1900_TO_1970_SECONDS;
        long ntpFraction = (millis * TWO_POW_32) / 1000L;
        writeUnsignedInt(buffer, offset, ntpSeconds);
        writeUnsignedInt(buffer, offset + 4, ntpFraction);
    }

    static long readTimestamp(byte[] buffer, int offset) {
        if (buffer == null || offset < 0 || offset + 8 > buffer.length) {
            throw new IllegalArgumentException("Timestamp buffer range is invalid");
        }
        long seconds = readUnsignedInt(buffer, offset);
        long fraction = readUnsignedInt(buffer, offset + 4);
        if (seconds == 0L && fraction == 0L) {
            return 0L;
        }
        long unixSeconds = seconds - OFFSET_1900_TO_1970_SECONDS;
        long millis = (fraction * 1000L + TWO_POW_32 / 2L) / TWO_POW_32;
        return unixSeconds * 1000L + millis;
    }

    private static void writeUnsignedInt(byte[] buffer, int offset, long value) {
        buffer[offset] = (byte) (value >> 24);
        buffer[offset + 1] = (byte) (value >> 16);
        buffer[offset + 2] = (byte) (value >> 8);
        buffer[offset + 3] = (byte) value;
    }

    private static long readUnsignedInt(byte[] buffer, int offset) {
        return ((long) buffer[offset] & 0xffL) << 24
                | ((long) buffer[offset + 1] & 0xffL) << 16
                | ((long) buffer[offset + 2] & 0xffL) << 8
                | ((long) buffer[offset + 3] & 0xffL);
    }

    public static final class Result {
        public final long serverTimeMillis;
        public final long roundTripMillis;
        public final long localClockOffsetMillis;
        public final int stratum;

        Result(long serverTimeMillis,
               long roundTripMillis,
               long localClockOffsetMillis,
               int stratum) {
            this.serverTimeMillis = serverTimeMillis;
            this.roundTripMillis = roundTripMillis;
            this.localClockOffsetMillis = localClockOffsetMillis;
            this.stratum = stratum;
        }
    }
}

package ai.hypermemetic.voicevault;

import java.io.File;
import java.io.IOException;
import java.io.RandomAccessFile;

/**
 * Canonical 16 kHz mono PCM16 WAV header, byte-identical to
 * {@code encodePcm16Wav} in {@code src/wav.mjs}. The phone and the server
 * therefore hash the same bytes whether audio is streamed or uploaded.
 */
final class WavHeader {
    static final int SIZE = 44;

    private WavHeader() {}

    static byte[] bytes(long pcmBytes) {
        long n = Math.min(Math.max(pcmBytes, 0), 0xffffffffL - 36);
        byte[] header = new byte[SIZE];
        ascii(header, 0, "RIFF");
        uint32(header, 4, 36 + n);
        ascii(header, 8, "WAVE");
        ascii(header, 12, "fmt ");
        uint32(header, 16, 16);
        uint16(header, 20, 1);
        uint16(header, 22, 1);
        uint32(header, 24, 16000);
        uint32(header, 28, 32000);
        uint16(header, 32, 2);
        uint16(header, 34, 16);
        ascii(header, 36, "data");
        uint32(header, 40, n);
        return header;
    }

    /** Patch the header, truncate to the canonical length and commit it. */
    static void finish(File file, long pcmBytes) throws IOException {
        try (RandomAccessFile out = new RandomAccessFile(file, "rw")) {
            out.seek(0);
            out.write(bytes(pcmBytes));
            out.setLength(SIZE + pcmBytes);
            out.getFD().sync();
        }
    }

    /** Duration of a canonical WAV, or -1 when the file cannot be parsed. */
    static long durationMs(File file) {
        try (RandomAccessFile in = new RandomAccessFile(file, "r")) {
            if (in.length() < SIZE) return -1;
            byte[] head = new byte[SIZE];
            in.readFully(head);
            if (!"RIFF".equals(text(head, 0, 4)) || !"WAVE".equals(text(head, 8, 4))) return -1;
            if (!"fmt ".equals(text(head, 12, 4)) || !"data".equals(text(head, 36, 4))) return -1;
            if (unsigned(head, 24) != 16000 || unsigned(head, 22) != 1 || unsigned(head, 34) != 16) return -1;
            return unsigned(head, 40) * 1000L / 32000L;
        } catch (IOException invalid) {
            return -1;
        }
    }

    private static void ascii(byte[] target, int offset, String value) {
        for (int i = 0; i < value.length(); i++) target[offset + i] = (byte) value.charAt(i);
    }

    private static void uint16(byte[] target, int offset, int value) {
        target[offset] = (byte) value;
        target[offset + 1] = (byte) (value >>> 8);
    }

    private static void uint32(byte[] target, int offset, long value) {
        target[offset] = (byte) value;
        target[offset + 1] = (byte) (value >>> 8);
        target[offset + 2] = (byte) (value >>> 16);
        target[offset + 3] = (byte) (value >>> 24);
    }

    private static long unsigned(byte[] data, int offset) {
        return (data[offset] & 0xffL) | ((data[offset + 1] & 0xffL) << 8)
                | ((data[offset + 2] & 0xffL) << 16) | ((data[offset + 3] & 0xffL) << 24);
    }

    private static String text(byte[] data, int offset, int length) {
        StringBuilder value = new StringBuilder(length);
        for (int i = 0; i < length; i++) value.append((char) (data[offset + i] & 0xff));
        return value.toString();
    }
}

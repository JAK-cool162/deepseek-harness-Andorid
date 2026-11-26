package ai.deepseek.harness;

import java.io.ByteArrayOutputStream;
import java.io.IOException;
import java.io.InputStream;
import java.io.OutputStream;

/** Stream helpers for Android versions whose InputStream lacks readAllBytes, readNBytes, and transferTo (before API 33). */
final class Io {
    private Io() {
    }

    /** @return every remaining byte of the stream */
    static byte[] readAll(InputStream in) throws IOException {
        ByteArrayOutputStream out = new ByteArrayOutputStream();
        copy(in, out);
        return out.toByteArray();
    }

    /** @return up to {@code count} bytes; fewer only at end of stream */
    static byte[] readN(InputStream in, int count) throws IOException {
        byte[] buffer = new byte[count];
        int read = 0;
        while (read < count) {
            int n = in.read(buffer, read, count - read);
            if (n < 0) break;
            read += n;
        }
        if (read == count) return buffer;
        byte[] shorter = new byte[read];
        System.arraycopy(buffer, 0, shorter, 0, read);
        return shorter;
    }

    /** Skip exactly {@code count} bytes. */
    static void skipN(InputStream in, long count) throws IOException {
        byte[] buffer = new byte[8192];
        long left = count;
        while (left > 0) {
            int n = in.read(buffer, 0, (int) Math.min(buffer.length, left));
            if (n < 0) throw new IOException("unexpected end of stream");
            left -= n;
        }
    }

    /** Copy the rest of {@code in} to {@code out}. */
    static void copy(InputStream in, OutputStream out) throws IOException {
        byte[] buffer = new byte[64 * 1024];
        for (int n = in.read(buffer); n >= 0; n = in.read(buffer)) out.write(buffer, 0, n);
    }
}

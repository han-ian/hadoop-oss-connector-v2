package com.alibaba.oss.connector;

import java.io.IOException;
import java.io.InputStream;

/**
 * Streaming InputStream that reads from a native OssObject on demand.
 *
 * <p>Unlike the previous eager-loading approach (which allocated a full-size
 * byte[] and copied all data into memory before returning), this class reads
 * bytes directly from native into the caller's buffer via
 * {@link NativeBinding#readInto}, using {@code GetPrimitiveArrayCritical}
 * to avoid intermediate copies.
 *
 * <p>Memory: O(1) — no heap allocation proportional to file size.
 * <p>JNI crossings: one per {@code read()} call from the caller.
 */
class JniOssInputStream extends InputStream {

    private OssObject obj;
    private long remaining;

    JniOssInputStream(OssObject obj, long length) {
        this.obj = obj;
        this.remaining = length;
    }

    @Override
    public int read() throws IOException {
        byte[] single = new byte[1];
        int n = read(single, 0, 1);
        return n <= 0 ? -1 : (single[0] & 0xFF);
    }

    @Override
    public int read(byte[] buf, int off, int len) throws IOException {
        if (obj == null) {
            throw new IOException("Stream is closed");
        }
        if (remaining <= 0) {
            return -1;
        }
        if (len == 0) {
            return 0;
        }

        int toRead = (int) Math.min(len, remaining);
        // Direct native read into caller's buffer at [off, off+toRead).
        // Uses GetPrimitiveArrayCritical — no intermediate copy.
        int n = NativeBinding.readInto(obj.handle(), buf, off, toRead);
        if (n > 0) {
            remaining -= n;
        } else if (n == 0) {
            // Native returned 0 — treat as EOF
            remaining = 0;
            return -1;
        }
        return n;
    }

    @Override
    public void close() {
        if (obj != null) {
            obj.close();
            obj = null;
        }
    }
}

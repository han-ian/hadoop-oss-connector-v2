package com.alibaba.oss.connector;

/**
 * File-like wrapper around a native OsscObject handle.
 */
public final class OssObject implements AutoCloseable {

    private long handle;

    OssObject(long handle) {
        this.handle = handle;
    }

    /** Read up to buf.length bytes into buf. Returns bytes actually read. */
    public int read(byte[] buf) {
        ensureOpen();
        return NativeBinding.read(handle, buf, buf.length);
    }

    /** Read up to count bytes into buf. Returns bytes actually read. */
    public int read(byte[] buf, int count) {
        ensureOpen();
        return NativeBinding.read(handle, buf, count);
    }

    /** Write data.length bytes from data. Returns bytes actually written. */
    public int write(byte[] data) {
        ensureOpen();
        return NativeBinding.write(handle, data, data.length);
    }

    /** Write count bytes from data. Returns bytes actually written. */
    public int write(byte[] data, int count) {
        ensureOpen();
        return NativeBinding.write(handle, data, count);
    }

    /** Seek to the given offset. Whence: 0=SET, 1=CUR, 2=END. */
    public long seek(long offset, int whence) {
        ensureOpen();
        return NativeBinding.seek(handle, offset, whence);
    }

    /** Return the current position. */
    public long tell() {
        ensureOpen();
        return NativeBinding.tell(handle);
    }

    /** Flush buffered writes. */
    public void flush() {
        ensureOpen();
        NativeBinding.flush(handle);
    }

    /** Whether this object supports seeking. */
    public boolean seekable() {
        ensureOpen();
        return NativeBinding.seekable(handle);
    }

    /** Object key (path). */
    public String key() {
        ensureOpen();
        return NativeBinding.objectKey(handle);
    }

    /** Content length in bytes. */
    public long contentLength() {
        ensureOpen();
        return NativeBinding.objectSize(handle);
    }

    /** Object label. */
    public String label() {
        ensureOpen();
        return NativeBinding.objectLabel(handle);
    }

    /** Native error code, 0 if none. */
    public int err() {
        ensureOpen();
        return NativeBinding.objectErr(handle);
    }

    /** Human-readable error message, or empty string. */
    public String errorMsg() {
        ensureOpen();
        return NativeBinding.objectErrorMsg(handle);
    }

    @Override
    public void close() {
        if (handle != 0) {
            NativeBinding.close(handle);
            handle = 0;
        }
    }

    long handle() {
        return handle;
    }

    private void ensureOpen() {
        if (handle == 0) {
            throw new IllegalStateException("OssObject is closed");
        }
    }
}

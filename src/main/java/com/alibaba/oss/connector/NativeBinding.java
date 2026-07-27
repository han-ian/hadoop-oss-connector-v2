package com.alibaba.oss.connector;

/**
 * Package-private JNI native method declarations.
 * All native pointers are passed as long (cast from C pointer types).
 */
class NativeBinding {

    static {
        System.loadLibrary("oss_connector_jni");
    }

    private NativeBinding() {}

    // --- Version and error ---

    static native String version();

    static native String lastError();

    // --- Client lifecycle ---

    /** Returns client handle on success, throws ServiceException on failure. */
    static native long newClient(String endpoint, String credPath,
                                 String configPath, String configJson,
                                 String uuid, int id, int total,
                                 String region);

    static native void destroyClient(long client);

    // --- Object operations ---

    /** Returns OsscObject handle on success, throws on failure. */
    static native long getObject(long client, String bucket, String key,
                                 long size, int type, String label);

    /** Returns OsscObject handle on success, throws on failure. */
    static native long putObject(long client, String bucket, String key);

    /** Returns content length directly, throws on failure. */
    static native long headObject(long client, String bucket, String key);

    /** Throws on failure. */
    static native void deleteObject(long client, String bucket, String key);

    /** Throws on failure. */
    static native void renameObject(long client, String bucket, String key,
                                    String newBucket, String newKey);

    // --- Object I/O ---

    /** Returns bytes read, throws on error. */
    static native int read(long obj, byte[] buf, int count);

    /** Returns bytes written, throws on error. */
    static native int write(long obj, byte[] data, int count);

    /** Returns new position, throws on error. */
    static native long seek(long obj, long offset, int whence);

    /** Returns current position. */
    static native long tell(long obj);

    /** Throws on error. */
    static native void flush(long obj);

    /** Throws on error. */
    static native void close(long obj);

    /** Returns true if seekable. */
    static native boolean seekable(long obj);

    // --- Object attributes ---

    static native String objectKey(long obj);

    static native long objectSize(long obj);

    static native String objectLabel(long obj);

    static native int objectErr(long obj);

    static native String objectErrorMsg(long obj);

    // --- List / Iterator ---

    /** Returns iterator handle, throws on failure. */
    static native long listObjects(long client, String bucket, String prefix);

    /** Returns OsscObject handle, 0 when iteration ends, throws on error. */
    static native long iterNext(long iter);

    /** Returns number of elements. */
    static native long iterLen(long iter);

    static native void iterDestroy(long iter);
}

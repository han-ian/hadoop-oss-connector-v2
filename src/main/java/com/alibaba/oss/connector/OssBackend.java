package com.alibaba.oss.connector;

import java.io.InputStream;
import java.util.List;

/**
 * Abstract OSS client backend interface.
 *
 * <p>Implementations:
 * <ul>
 *   <li>{@link JniOssBackend} — wraps OssClient (C++ native via JNI)</li>
 *   <li>{@link JavaSdkOssBackend} — wraps alibabacloud-oss-v2 Java SDK</li>
 * </ul>
 *
 * <p>Both backends expose the same operations required by Hadoop OssManager,
 * enabling on-demand replacement for benchmarking.
 */
public interface OssBackend extends AutoCloseable {

    /**
     * Read a byte range from an object.
     *
     * @param bucket    bucket name
     * @param key       object key
     * @param offset    start offset (0-based)
     * @param length    number of bytes to read
     * @return input stream for reading the range
     */
    InputStream getObject(String bucket, String key, long offset, long length);

    /**
     * Get object content length (HEAD request).
     *
     * @param bucket bucket name
     * @param key    object key
     * @return content length in bytes
     */
    long headObject(String bucket, String key);

    /**
     * List objects with prefix.
     *
     * @param bucket bucket name
     * @param prefix key prefix
     * @return list of object summaries
     */
    List<com.alibaba.oss.connector.models.ObjectSummary> listObjects(String bucket, String prefix);

    /**
     * Delete a single object.
     *
     * @param bucket bucket name
     * @param key    object key
     */
    void deleteObject(String bucket, String key);

    /**
     * Write an object (small file, single PUT).
     *
     * @param bucket bucket name
     * @param key    object key
     * @param data   content bytes
     * @return ETag of the uploaded object
     */
    String putObject(String bucket, String key, byte[] data);

    /**
     * Return backend name for metrics/logging.
     */
    String backendName();
}

package com.alibaba.oss.connector;

import com.alibaba.oss.connector.models.*;

import java.io.ByteArrayInputStream;
import java.io.InputStream;
import java.util.List;

/**
 * JNI backend — delegates all operations to the native C++ OssClient via JNI.
 *
 * <p>Requires {@code liboss_connector_jni.so} (or .dylib) on {@code java.library.path}.
 */
public class JniOssBackend implements OssBackend {

    private final OssClient client;

    /**
     * Create a JNI backend with full parameters.
     *
     * @param endpoint   OSS endpoint
     * @param credPath   credential file path, or null
     * @param configPath config file path, or null
     * @param configJson inline JSON config, or null
     * @param region     OSS region, or null
     */
    public JniOssBackend(String endpoint, String credPath, String configPath,
                         String configJson, String region) {
        this.client = new OssClient(endpoint, credPath, configPath,
                                     configJson, null, 0, 1, region);
    }

    /** Simple constructor with just endpoint. */
    public JniOssBackend(String endpoint) {
        this(endpoint, null, null, null, null);
    }

    @Override
    public InputStream getObject(String bucket, String key, long offset, long length) {
        GetObjectRequest req = GetObjectRequest.builder()
                .bucket(bucket)
                .key(key)
                .size(length)
                .build();

        try (GetObjectResult result = client.getObject(req)) {
            OssObject obj = result.body();

            // Seek to offset if needed
            if (offset > 0) {
                obj.seek(offset, 0); // SEEK_SET
            }

            // Read all requested bytes into memory
            // (For benchmarking simplicity; production would use streaming)
            byte[] buf = new byte[(int) length];
            int totalRead = 0;
            while (totalRead < length) {
                // OssObject.read(byte[], count) reads from current position
                int remaining = (int) (length - totalRead);
                byte[] tmp = new byte[remaining];
                int n = obj.read(tmp, remaining);
                if (n <= 0) break;
                System.arraycopy(tmp, 0, buf, totalRead, n);
                totalRead += n;
            }
            return new ByteArrayInputStream(buf, 0, totalRead);
        }
    }

    @Override
    public long headObject(String bucket, String key) {
        HeadObjectRequest req = HeadObjectRequest.builder()
                .bucket(bucket)
                .key(key)
                .build();
        HeadObjectResult result = client.headObject(req);
        return result.contentLength();
    }

    @Override
    public List<ObjectSummary> listObjects(String bucket, String prefix) {
        ListObjectsRequest req = ListObjectsRequest.builder()
                .bucket(bucket)
                .prefix(prefix)
                .build();
        try (ListObjectsResult result = client.listObjects(req)) {
            return result.contents();
        }
    }

    @Override
    public void deleteObject(String bucket, String key) {
        DeleteObjectRequest req = DeleteObjectRequest.builder()
                .bucket(bucket)
                .key(key)
                .build();
        client.deleteObject(req);
    }

    @Override
    public String putObject(String bucket, String key, byte[] data) {
        PutObjectRequest req = PutObjectRequest.builder()
                .bucket(bucket)
                .key(key)
                .build();
        try (PutObjectResult result = client.putObject(req)) {
            OssObject obj = result.body();
            obj.write(data);
            obj.flush();
            return ""; // ETag not directly available from current API
        }
    }

    @Override
    public String backendName() {
        return "JNI (" + OssClient.version() + ")";
    }

    @Override
    public void close() {
        client.close();
    }
}

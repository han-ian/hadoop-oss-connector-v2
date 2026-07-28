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
        // Eager loading: read the requested range into a single byte[].
        // NOTE: the native getObject `size` param declares the object's readable
        // window [0, size) — it is set as ROSIZE on the underlying HTTP file.
        // For a range read we must pass offset+length (not length!), otherwise
        // seek(offset) lands beyond the declared size and pread returns 0 (EIO).
        // Improvement over original: uses readInto (GetPrimitiveArrayCritical)
        // instead of read (GetByteArrayElements) for guaranteed zero-copy pin.
        long objHandle = NativeBinding.getObject(client.handle(),
                bucket, key, offset + length, 0, null);
        try (OssObject obj = new OssObject(objHandle)) {
            if (offset > 0) {
                obj.seek(offset, 0); // SEEK_SET
            }
            byte[] result = new byte[(int) length];
            int totalRead = 0;
            while (totalRead < length) {
                int remaining = (int) (length - totalRead);
                int n = NativeBinding.readInto(obj.handle(), result, totalRead, remaining);
                if (n <= 0) break;
                totalRead += n;
            }
            if (totalRead != length) {
                // Fail loudly — a short read here means wrong data, not EOF
                throw new IllegalStateException("short read: " + bucket + "/" + key
                        + " offset=" + offset + " length=" + length + " read=" + totalRead);
            }
            return new ByteArrayInputStream(result);
        }
    }

    @Override
    public int pread(String bucket, String key, byte[] buf, long offset, int length) {
        // Open object with size = offset + length (ROSIZE covers the read window)
        long objHandle = NativeBinding.getObject(client.handle(),
                bucket, key, offset + length, 0, null);
        try (OssObject obj = new OssObject(objHandle)) {
            if (offset > 0) {
                obj.seek(offset, 0); // SEEK_SET
            }
            int totalRead = 0;
            while (totalRead < length) {
                int remaining = length - totalRead;
                int n = NativeBinding.readInto(obj.handle(), buf, totalRead, remaining);
                if (n <= 0) break;
                totalRead += n;
            }
            if (totalRead != length) {
                throw new IllegalStateException("pread short read: " + bucket + "/" + key
                        + " offset=" + offset + " length=" + length + " read=" + totalRead);
            }
            return totalRead;
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

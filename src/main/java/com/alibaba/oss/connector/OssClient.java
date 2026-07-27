package com.alibaba.oss.connector;

import com.alibaba.oss.connector.models.*;

/**
 * Public SDK-style client for the OSS Connector shared library.
 *
 * <p>Usage:
 * <pre>{@code
 * try (OssClient client = new OssClient("oss-cn-hangzhou.aliyuncs.com")) {
 *     try (GetObjectResult result = client.getObject(
 *             GetObjectRequest.builder()
 *                 .bucket("my-bucket")
 *                 .key("my-key")
 *                 .build())) {
 *         byte[] buf = new byte[4096];
 *         int n = result.body().read(buf);
 *     }
 * }
 * }</pre>
 */
public final class OssClient implements AutoCloseable {

    private long handle;

    /**
     * Create a client with only an endpoint (uses default credential and config paths).
     */
    public OssClient(String endpoint) {
        this(endpoint, null, null, null, null, 0, 1, null);
    }

    /**
     * Create a client with full parameters.
     *
     * @param endpoint   OSS endpoint
     * @param credPath   credential file path, or null
     * @param configPath config file path, or null
     * @param configJson inline JSON config, or null
     * @param uuid       session UUID, or null
     * @param id         worker id (0-based)
     * @param total      total workers
     * @param region     OSS region, or null
     */
    public OssClient(String endpoint, String credPath, String configPath,
                     String configJson, String uuid, int id, int total,
                     String region) {
        this.handle = NativeBinding.newClient(endpoint, credPath, configPath,
                                              configJson, uuid, id, total, region);
    }

    /** Return the library version string. */
    public static String version() {
        return NativeBinding.version();
    }

    /**
     * Get an object from OSS.
     */
    public GetObjectResult getObject(GetObjectRequest request) {
        ensureOpen();
        long objHandle = NativeBinding.getObject(handle,
                request.bucket(), request.key(),
                request.size(), request.type(), request.label());
        return new GetObjectResult(new OssObject(objHandle));
    }

    /**
     * Create/open an object for writing.
     */
    public PutObjectResult putObject(PutObjectRequest request) {
        ensureOpen();
        long objHandle = NativeBinding.putObject(handle,
                request.bucket(), request.key());
        return new PutObjectResult(new OssObject(objHandle));
    }

    /**
     * Retrieve object metadata (content length).
     */
    public HeadObjectResult headObject(HeadObjectRequest request) {
        ensureOpen();
        long size = NativeBinding.headObject(handle,
                request.bucket(), request.key());
        return new HeadObjectResult(size);
    }

    /**
     * Delete an object.
     */
    public DeleteObjectResult deleteObject(DeleteObjectRequest request) {
        ensureOpen();
        NativeBinding.deleteObject(handle,
                request.bucket(), request.key());
        return new DeleteObjectResult();
    }

    /**
     * List objects with a given prefix.
     */
    public ListObjectsResult listObjects(ListObjectsRequest request) {
        ensureOpen();
        long iterHandle = NativeBinding.listObjects(handle,
                request.bucket(), request.prefix());
        return new ListObjectsResult(iterHandle);
    }

    @Override
    public void close() {
        if (handle != 0) {
            NativeBinding.destroyClient(handle);
            handle = 0;
        }
    }

    private void ensureOpen() {
        if (handle == 0) {
            throw new IllegalStateException("OssClient is closed");
        }
    }
}

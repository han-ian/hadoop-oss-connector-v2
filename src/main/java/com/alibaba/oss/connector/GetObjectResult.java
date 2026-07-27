package com.alibaba.oss.connector;

/**
 * Result of a getObject operation, wrapping the returned OssObject.
 */
public final class GetObjectResult implements AutoCloseable {

    private final OssObject body;

    GetObjectResult(OssObject body) {
        this.body = body;
    }

    /** The file-like object body for reading content. */
    public OssObject body() {
        return body;
    }

    /** Object key (path). */
    public String key() {
        return body.key();
    }

    /** Content length in bytes. */
    public long contentLength() {
        return body.contentLength();
    }

    @Override
    public void close() {
        body.close();
    }
}

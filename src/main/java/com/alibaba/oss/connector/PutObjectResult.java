package com.alibaba.oss.connector;

/**
 * Result of a putObject operation, wrapping the writable OssObject.
 */
public final class PutObjectResult implements AutoCloseable {

    private final OssObject body;

    PutObjectResult(OssObject body) {
        this.body = body;
    }

    /** The file-like object body for writing content. */
    public OssObject body() {
        return body;
    }

    @Override
    public void close() {
        body.close();
    }
}

package com.alibaba.oss.connector;

/**
 * Result of a headObject operation, containing object metadata.
 */
public final class HeadObjectResult {

    private final long contentLength;

    HeadObjectResult(long contentLength) {
        this.contentLength = contentLength;
    }

    /** Content length in bytes. */
    public long contentLength() {
        return contentLength;
    }
}

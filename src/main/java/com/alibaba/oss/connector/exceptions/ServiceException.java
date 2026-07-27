package com.alibaba.oss.connector.exceptions;

/**
 * Unchecked exception thrown when a native OSS connector operation fails.
 */
public class ServiceException extends RuntimeException {

    private final int errno;
    private final String errorCode;

    public ServiceException(String message) {
        this(message, -1, "");
    }

    public ServiceException(String message, int errno, String errorCode) {
        super(message);
        this.errno = errno;
        this.errorCode = errorCode;
    }

    /** Native errno value, or -1 if unavailable. */
    public int errno() {
        return errno;
    }

    /** Machine-readable error code, or empty string if unavailable. */
    public String errorCode() {
        return errorCode;
    }
}

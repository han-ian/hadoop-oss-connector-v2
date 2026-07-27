package com.alibaba.oss.connector.models;

/**
 * Request for listing objects in an OSS bucket.
 */
public final class ListObjectsRequest {

    private final String bucket;
    private final String prefix;

    private ListObjectsRequest(Builder b) {
        this.bucket = b.bucket;
        this.prefix = b.prefix;
    }

    public String bucket() { return bucket; }
    public String prefix() { return prefix; }

    public static Builder builder() {
        return new Builder();
    }

    public static final class Builder {
        private String bucket;
        private String prefix = "";

        private Builder() {}

        public Builder bucket(String bucket) { this.bucket = bucket; return this; }
        public Builder prefix(String prefix) { this.prefix = prefix; return this; }

        public ListObjectsRequest build() {
            if (bucket == null) throw new IllegalArgumentException("bucket is required");
            return new ListObjectsRequest(this);
        }
    }
}

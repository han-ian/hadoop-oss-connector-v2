package com.alibaba.oss.connector.models;

/**
 * Request for putting an object to OSS.
 */
public final class PutObjectRequest {

    private final String bucket;
    private final String key;

    private PutObjectRequest(Builder b) {
        this.bucket = b.bucket;
        this.key = b.key;
    }

    public String bucket() { return bucket; }
    public String key()    { return key; }

    public static Builder builder() {
        return new Builder();
    }

    public static final class Builder {
        private String bucket;
        private String key;

        private Builder() {}

        public Builder bucket(String bucket) { this.bucket = bucket; return this; }
        public Builder key(String key)       { this.key = key; return this; }

        public PutObjectRequest build() {
            if (bucket == null) throw new IllegalArgumentException("bucket is required");
            if (key == null) throw new IllegalArgumentException("key is required");
            return new PutObjectRequest(this);
        }
    }
}

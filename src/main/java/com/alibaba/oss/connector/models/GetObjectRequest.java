package com.alibaba.oss.connector.models;

/**
 * Request for getting an object from OSS.
 */
public final class GetObjectRequest {

    private final String bucket;
    private final String key;
    private final long size;
    private final int type;
    private final String label;

    private GetObjectRequest(Builder b) {
        this.bucket = b.bucket;
        this.key = b.key;
        this.size = b.size;
        this.type = b.type;
        this.label = b.label;
    }

    public String bucket() { return bucket; }
    public String key()    { return key; }
    public long size()     { return size; }
    public int type()      { return type; }
    public String label()  { return label; }

    public static Builder builder() {
        return new Builder();
    }

    public static final class Builder {
        private String bucket;
        private String key;
        private long size = 0;
        private int type = 0;
        private String label = "";

        private Builder() {}

        public Builder bucket(String bucket) { this.bucket = bucket; return this; }
        public Builder key(String key)       { this.key = key; return this; }
        public Builder size(long size)       { this.size = size; return this; }
        public Builder type(int type)        { this.type = type; return this; }
        public Builder label(String label)   { this.label = label; return this; }

        public GetObjectRequest build() {
            if (bucket == null) throw new IllegalArgumentException("bucket is required");
            if (key == null) throw new IllegalArgumentException("key is required");
            return new GetObjectRequest(this);
        }
    }
}

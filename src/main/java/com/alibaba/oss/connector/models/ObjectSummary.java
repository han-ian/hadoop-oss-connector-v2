package com.alibaba.oss.connector.models;

/**
 * Lightweight summary of an OSS object returned by list operations.
 */
public final class ObjectSummary {

    private final String key;
    private final long size;
    private final String label;

    public ObjectSummary(String key, long size, String label) {
        this.key = key;
        this.size = size;
        this.label = label;
    }

    public String key() {
        return key;
    }

    public long size() {
        return size;
    }

    public String label() {
        return label;
    }

    @Override
    public String toString() {
        return "ObjectSummary{key='" + key + "', size=" + size + ", label='" + label + "'}";
    }
}

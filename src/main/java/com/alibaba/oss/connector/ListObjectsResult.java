package com.alibaba.oss.connector;

import com.alibaba.oss.connector.models.ObjectSummary;

import java.util.ArrayList;
import java.util.Collections;
import java.util.List;

/**
 * Result of a listObjects operation. Lazily materializes summaries from the native iterator.
 */
public final class ListObjectsResult implements AutoCloseable {

    private long iterHandle;
    private List<ObjectSummary> contents;

    ListObjectsResult(long iterHandle) {
        this.iterHandle = iterHandle;
    }

    /** Number of objects in this listing. */
    public long keyCount() {
        ensureOpen();
        return NativeBinding.iterLen(iterHandle);
    }

    /** Materialized list of object summaries. Iterates the native iterator on first call. */
    public List<ObjectSummary> contents() {
        ensureOpen();
        if (contents == null) {
            contents = materialize();
        }
        return contents;
    }

    @Override
    public void close() {
        if (iterHandle != 0) {
            NativeBinding.iterDestroy(iterHandle);
            iterHandle = 0;
        }
    }

    private List<ObjectSummary> materialize() {
        List<ObjectSummary> list = new ArrayList<>();
        while (true) {
            long objHandle = NativeBinding.iterNext(iterHandle);
            if (objHandle == 0) {
                break;
            }
            String key = NativeBinding.objectKey(objHandle);
            long size = NativeBinding.objectSize(objHandle);
            String label = NativeBinding.objectLabel(objHandle);
            NativeBinding.close(objHandle);
            list.add(new ObjectSummary(key, size, label));
        }
        return Collections.unmodifiableList(list);
    }

    private void ensureOpen() {
        if (iterHandle == 0) {
            throw new IllegalStateException("ListObjectsResult is closed");
        }
    }
}

package com.alibaba.oss.connector.bench;

import com.alibaba.oss.connector.*;

import org.openjdk.jmh.annotations.*;
import org.openjdk.jmh.infra.Blackhole;
import org.openjdk.jmh.profile.GCProfiler;
import org.openjdk.jmh.results.format.ResultFormatType;
import org.openjdk.jmh.runner.Runner;
import org.openjdk.jmh.runner.options.Options;
import org.openjdk.jmh.runner.options.OptionsBuilder;

import com.sun.management.OperatingSystemMXBean;

import java.io.InputStream;
import java.lang.management.ManagementFactory;
import java.util.concurrent.TimeUnit;

/**
 * JMH Benchmark: JNI backend vs Java SDK backend for OSS operations.
 *
 * <p>Tests multiple file sizes: 64KB, 1MB, 4MB, 32MB, 64MB, 128MB, 512MB.
 * Each size has N copies on OSS (anti-cache key rotation).
 *
 * <p>Config via system properties:
 * <pre>
 *   -Doss.endpoint=oss-cn-beijing-internal.aliyuncs.com
 *   -Doss.bucket=hy-test-dadi-2026-bj
 *   -Doss.keyPrefix=bench-data      (default)
 *   -Doss.region=cn-beijing
 *   -Doss.keyCount=10               (anti-cache copies per size)
 * </pre>
 */
@BenchmarkMode({Mode.AverageTime, Mode.Throughput})
@OutputTimeUnit(TimeUnit.MILLISECONDS)
@State(Scope.Thread)
@Fork(value = 1, jvmArgsAppend = {
        "-Djava.library.path=" + OssBackendBenchmark.NATIVE_LIB_HINT
})
@Warmup(iterations = 2, time = 3, timeUnit = TimeUnit.SECONDS)
@Measurement(iterations = 3, time = 5, timeUnit = TimeUnit.SECONDS)
public class OssBackendBenchmark {

    static final String NATIVE_LIB_HINT = "/tmp/mini-sdk-bench/lib";

    // ── File size labels → byte sizes ──

    private static final long[][] SIZE_TABLE = {
        // {bytes,   label_hash}  — label used in key construction
        {64 * 1024,            0},  // 64kb
        {1024 * 1024,          1},  // 1mb
        {4 * 1024 * 1024,      2},  // 4mb
        {32 * 1024 * 1024,     3},  // 32mb
        {64 * 1024 * 1024,     4},  // 64mb
        {128 * 1024 * 1024,    5},  // 128mb
        {512 * 1024 * 1024,    6},  // 512mb
    };

    // ── Parametrized dimensions ──

    @Param({"jni", "java"})
    private String backend;

    @Param({"64kb", "1mb", "4mb", "32mb", "64mb", "128mb", "512mb"})
    private String fileSize;

    // ── OSS config ──

    private String endpoint;
    private String bucket;
    private String region;
    private String accessKeyId;
    private String accessKeySecret;
    private String[] keys;
    private int keyIndex = 0;

    // ── Backend ──

    private OssBackend client;
    private long fileSizeBytes;

    // ── Resource tracking ──

    private OperatingSystemMXBean osMxBean;
    private long cpuTimeStartNs;
    private long heapUsedStart;

    @Setup(Level.Trial)
    public void setup() {
        endpoint = requireProp("oss.endpoint");
        bucket   = requireProp("oss.bucket");
        region   = System.getProperty("oss.region", "");

        accessKeyId     = System.getenv("OSS_ACCESS_KEY_ID");
        accessKeySecret = System.getenv("OSS_ACCESS_KEY_SECRET");
        if (accessKeyId == null || accessKeySecret == null) {
            throw new IllegalStateException("OSS_ACCESS_KEY_ID / OSS_ACCESS_KEY_SECRET not set");
        }

        // Parse file size
        fileSizeBytes = parseSize(fileSize);

        // Key rotation: bench-data/{fileSize}_{i}.bin
        String keyPrefix = System.getProperty("oss.keyPrefix", "bench-data");
        int keyCount = Integer.parseInt(System.getProperty("oss.keyCount", "1"));
        keys = generateKeys(keyPrefix, fileSize, keyCount);

        // Create backend
        switch (backend) {
            case "jni":
                client = new JniOssBackend(endpoint, null, null, null, region);
                break;
            case "java":
                client = new JavaSdkOssBackend(endpoint, accessKeyId, accessKeySecret, region, bucket);
                break;
            default:
                throw new IllegalArgumentException("Unknown backend: " + backend);
        }

        // Snapshot CPU & heap before benchmark
        osMxBean = ManagementFactory.getPlatformMXBean(OperatingSystemMXBean.class);
        System.gc();
        heapUsedStart = Runtime.getRuntime().totalMemory() - Runtime.getRuntime().freeMemory();
        cpuTimeStartNs = osMxBean.getProcessCpuTime();
        System.err.printf("[resource] trial start: heap=%.1fMB, cpuTime=%.1fms, backend=%s, fileSize=%s%n",
                heapUsedStart / 1048576.0, cpuTimeStartNs / 1e6, backend, fileSize);
    }

    @TearDown(Level.Trial)
    public void tearDown() throws Exception {
        long cpuTimeEndNs = osMxBean.getProcessCpuTime();
        long heapUsedEnd = Runtime.getRuntime().totalMemory() - Runtime.getRuntime().freeMemory();
        double cpuMs = (cpuTimeEndNs - cpuTimeStartNs) / 1e6;
        double heapDeltaMB = (heapUsedEnd - heapUsedStart) / 1048576.0;
        System.err.printf("[resource] trial end: cpuDelta=%.1fms, heapDelta=%.1fMB, heapFinal=%.1fMB, backend=%s, fileSize=%s%n",
                cpuMs, heapDeltaMB, heapUsedEnd / 1048576.0, backend, fileSize);

        if (client != null) {
            client.close();
        }
    }

    // ── Benchmarks ──

    @Benchmark
    public long headObject(Blackhole bh) {
        String k = nextKey();
        long size = client.headObject(bucket, k);
        bh.consume(size);
        return size;
    }

    /**
     * Measures the getObject() call only — what the backend does internally.
     *
     * <p>Semantics differ by backend:
     * <ul>
     *   <li>JNI eager: native HTTP fetch + full data loaded into byte[] (entire file in memory)</li>
     *   <li>Java SDK: HTTP connection established, returns streaming InputStream (no data read yet)</li>
     * </ul>
     *
     * <p>For JNI this measures "fetch entire file"; for Java SDK this measures
     * "open HTTP connection and get response headers". Use {@link #getObjectRead}
     * for end-to-end comparison.
     */
    @Benchmark
    public long getObject(Blackhole bh) {
        String k = nextKey();
        try (InputStream is = client.getObject(bucket, k, 0, fileSizeBytes)) {
            bh.consume(is);
            return is.available();
        } catch (Exception e) {
            throw new RuntimeException("getObject failed: " + bucket + "/" + k, e);
        }
    }

    /**
     * Measures the full pipeline: getObject() + read loop — what the caller experiences.
     *
     * <p>Reads the entire file in 64KB chunks (matching Hadoop's RemoteObjectReader
     * READ_BUFFER_SIZE = 64KB). This is the end-to-end cost for "get all bytes".
     *
     * <p>Read breakdown:
     * <ul>
     *   <li>JNI eager: getObject() loaded everything → read loop is just ByteArrayInputStream memory copy</li>
     *   <li>Java SDK: getObject() opened HTTP stream → read loop pulls from socket in 64KB chunks</li>
     * </ul>
     */
    @Benchmark
    public long getObjectRead(Blackhole bh) {
        String k = nextKey();
        long totalRead = 0;
        try (InputStream is = client.getObject(bucket, k, 0, fileSizeBytes)) {
            byte[] buf = new byte[65536];  // 64KB read buffer (matches Hadoop READ_BUFFER_SIZE)
            int n;
            while ((n = is.read(buf)) > 0) {
                totalRead += n;
            }
        } catch (Exception e) {
            throw new RuntimeException("getObjectRead failed: " + bucket + "/" + k, e);
        }
        bh.consume(totalRead);
        return totalRead;
    }

    // ── Main (JMH runner) ──

    public static void main(String[] args) throws Exception {
        String resultFile = System.getProperty("oss.resultFile", "/tmp/jmh-result.json");
        Options opt = new OptionsBuilder()
                .include(OssBackendBenchmark.class.getSimpleName())
                .addProfiler(GCProfiler.class)
                .resultFormat(ResultFormatType.JSON)
                .result(resultFile)
                .build();
        new Runner(opt).run();
    }

    // ── Helpers ──

    private String nextKey() {
        String k = keys[keyIndex % keys.length];
        keyIndex++;
        return k;
    }

    private static String requireProp(String name) {
        String v = System.getProperty(name);
        if (v == null || v.isEmpty()) {
            throw new IllegalStateException("Required system property: -D" + name + "=...");
        }
        return v;
    }

    /**
     * Parse human-readable size label to bytes.
     */
    static long parseSize(String label) {
        switch (label) {
            case "64kb":  return 64L * 1024;
            case "1mb":   return 1L * 1024 * 1024;
            case "4mb":   return 4L * 1024 * 1024;
            case "32mb":  return 32L * 1024 * 1024;
            case "64mb":  return 64L * 1024 * 1024;
            case "128mb": return 128L * 1024 * 1024;
            case "512mb": return 512L * 1024 * 1024;
            default:
                throw new IllegalArgumentException("Unknown fileSize: " + label);
        }
    }

    /**
     * Generate N keys for a given file size.
     * e.g. bench-data/4mb_0.bin, bench-data/4mb_1.bin, ...
     * If keyCount == 1, return bench-data/4mb.bin
     */
    static String[] generateKeys(String prefix, String sizeLabel, int count) {
        String[] result = new String[count];
        if (count == 1) {
            result[0] = prefix + "/" + sizeLabel + ".bin";
            return result;
        }
        for (int i = 0; i < count; i++) {
            result[i] = prefix + "/" + sizeLabel + "_" + i + ".bin";
        }
        return result;
    }
}

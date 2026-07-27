package com.alibaba.oss.connector.bench;

import com.alibaba.oss.connector.*;

import org.openjdk.jmh.annotations.*;
import org.openjdk.jmh.infra.Blackhole;
import org.openjdk.jmh.profile.GCProfiler;
import org.openjdk.jmh.results.format.ResultFormatType;
import org.openjdk.jmh.runner.Runner;
import org.openjdk.jmh.runner.options.Options;
import org.openjdk.jmh.runner.options.OptionsBuilder;
import org.openjdk.jmh.runner.options.TimeValue;

import com.sun.management.OperatingSystemMXBean;

import java.io.InputStream;
import java.lang.management.ManagementFactory;
import java.util.concurrent.TimeUnit;

/**
 * JMH Benchmark: JNI backend vs Java SDK backend for OSS operations.
 *
 * <p>Config via system properties:
 * <pre>
 *   -Doss.endpoint=oss-cn-beijing-internal.aliyuncs.com
 *   -Doss.bucket=hy-test-dadi-2026-bj
 *   -Doss.key=bench-data/4mb.bin
 *   -Doss.region=cn-beijing
 *   -Doss.keyCount=20        (optional, anti-cache key rotation)
 * </pre>
 *
 * <p>Run:
 * <pre>
 *   java -Djava.library.path=/path/to/native \
 *        -Doss.endpoint=... -Doss.bucket=... -Doss.key=... \
 *        -cp target/test-classes:target/classes:target/dependency/* \
 *        com.alibaba.oss.connector.bench.OssBackendBenchmark
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

    // ── Parametrized dimensions (JMH auto-generates all combinations) ──

    @Param({"jni", "java"})
    private String backend;

    @Param({"4096", "65536", "1048576", "4194304"})
    private String readSize;

    // ── OSS config (from system properties) ──

    private String endpoint;
    private String bucket;
    private String region;
    private String accessKeyId;
    private String accessKeySecret;
    private String[] keys;     // rotated key list (anti-cache)
    private int keyIndex = 0;

    // ── Backend ──

    private OssBackend client;
    private long readSizeLong;

    // ── Resource tracking (CPU / heap) ──

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

        // Key rotation: generate N keys from base key
        String baseKey = requireProp("oss.key");
        int keyCount = Integer.parseInt(System.getProperty("oss.keyCount", "1"));
        keys = generateKeys(baseKey, keyCount);

        readSizeLong = Long.parseLong(readSize);

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
        System.err.printf("[resource] trial start: heap=%.1fMB, cpuTime=%.1fms, backend=%s, readSize=%s%n",
                heapUsedStart / 1048576.0, cpuTimeStartNs / 1e6, backend, readSize);
    }

    @TearDown(Level.Trial)
    public void tearDown() throws Exception {
        // Snapshot CPU & heap after benchmark
        long cpuTimeEndNs = osMxBean.getProcessCpuTime();
        long heapUsedEnd = Runtime.getRuntime().totalMemory() - Runtime.getRuntime().freeMemory();
        double cpuMs = (cpuTimeEndNs - cpuTimeStartNs) / 1e6;
        double heapDeltaMB = (heapUsedEnd - heapUsedStart) / 1048576.0;
        System.err.printf("[resource] trial end: cpuDelta=%.1fms, heapDelta=%.1fMB, heapFinal=%.1fMB, backend=%s, readSize=%s%n",
                cpuMs, heapDeltaMB, heapUsedEnd / 1048576.0, backend, readSize);

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

    @Benchmark
    public long getObject(Blackhole bh) {
        String k = nextKey();
        long totalRead = 0;
        try (InputStream is = client.getObject(bucket, k, 0, readSizeLong)) {
            byte[] buf = new byte[8192];
            int n;
            while ((n = is.read(buf)) > 0) {
                totalRead += n;
            }
        } catch (Exception e) {
            throw new RuntimeException("getObject failed: " + bucket + "/" + k, e);
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
     * Generate N keys from a base key by inserting _N before the extension.
     * e.g. bench-data/4mb.bin → bench-data/4mb_0.bin, bench-data/4mb_1.bin, ...
     * If keyCount == 1, return the original key unchanged.
     */
    static String[] generateKeys(String baseKey, int count) {
        String[] result = new String[count];
        if (count == 1) {
            result[0] = baseKey;
            return result;
        }
        int dot = baseKey.lastIndexOf('.');
        String prefix = dot > 0 ? baseKey.substring(0, dot) : baseKey;
        String suffix = dot > 0 ? baseKey.substring(dot) : "";
        for (int i = 0; i < count; i++) {
            result[i] = prefix + "_" + i + suffix;
        }
        return result;
    }
}

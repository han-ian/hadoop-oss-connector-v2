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
 *   -Doss.rangeSize=131072           (range GET size, default 128KB = Hadoop PREFETCH_BLOCK_DEFAULT_SIZE)
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
    private int rangeSize;

    // ── Random pread config (parsed once per trial in setup) ──

    private int numRandomReads;
    private java.util.Random rng;

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

        // Parse range GET size (default 128KB = Hadoop PREFETCH_BLOCK_DEFAULT_SIZE)
        rangeSize = Integer.parseInt(System.getProperty("oss.rangeSize", String.valueOf(128 * 1024)));

        // Random pread config: parse once, seed once — offsets vary across invocations
        numRandomReads = Integer.parseInt(System.getProperty("oss.numRandomReads", "10"));
        rng = new java.util.Random(Long.parseLong(System.getProperty("oss.randomSeed", "42")));

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
        System.err.printf("[resource] trial start: heap=%.1fMB, cpuTime=%.1fms, backend=%s, fileSize=%s, rangeSize=%dKB%n",
                heapUsedStart / 1048576.0, cpuTimeStartNs / 1e6, backend, fileSize, rangeSize / 1024);
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

    // /**
    //  * Reads the entire file via multiple Range GET requests — matches Hadoop connector's
    //  * RemoteObjectReader.readOneBlock() pattern.
    //  *
    //  * <p>Each Range GET fetches {@code rangeSize} bytes (default 128KB, configurable via
    //  * {@code -Doss.rangeSize}). Total HTTP requests = ceil(fileSize / rangeSize).
    //  *
    //  * <p>Semantics per backend:
    //  * <ul>
    //  *   <li>JNI: each Range GET → native getObject(rangeSize) → PhotonLibOS HTTP Range GET</li>
    //  *   <li>Java SDK: each Range GET → client.getObject(range) → 1 HTTP Range GET</li>
    //  * </ul>
    //  */
    // @Benchmark
    // public long getObject(Blackhole bh) {
    //     String k = nextKey();
    //     long totalRead = 0;
    //     long offset = 0;
    //     while (offset < fileSizeBytes) {
    //         long len = Math.min(rangeSize, fileSizeBytes - offset);
    //         try (InputStream is = client.getObject(bucket, k, offset, len)) {
    //             bh.consume(is);
    //             totalRead += len;
    //         } catch (Exception e) {
    //             throw new RuntimeException("getObject failed at offset=" + offset, e);
    //         }
    //         offset += len;
    //     }
    //     return totalRead;
    // }

    /**
     * Full pipeline: Range GET + read loop — exactly matches Hadoop connector's
     * RemoteObjectReader.readOneBlock() implementation:
     * <pre>
     *   GetObjectResult result = remoteObject.openForRead(offset, readSize);  // 1 Range GET
     *   InputStream is = result.body();
     *   byte[] bytes = new byte[READ_BUFFER_SIZE];  // 64KB
     *   do { is.read(bytes, 0, min(64KB, remaining)); } while (...);
     * </pre>
     *
     * <p>Each Range GET fetches {@code rangeSize} bytes, then reads from the response
     * InputStream in 64KB chunks (Hadoop's READ_BUFFER_SIZE).
     */
    @Benchmark
    public long getObjectRead(Blackhole bh) {
        String k = nextKey();
        long totalRead = 0;
        long offset = 0;
        byte[] readBuf = new byte[65536];  // 64KB (matches Hadoop READ_BUFFER_SIZE)
        while (offset < fileSizeBytes) {
            long len = Math.min(rangeSize, fileSizeBytes - offset);
            try (InputStream is = client.getObject(bucket, k, offset, len)) {
                int remaining = (int) len;
                while (remaining > 0) {
                    int toRead = Math.min(readBuf.length, remaining);
                    int n = is.read(readBuf, 0, toRead);
                    if (n <= 0) break;
                    totalRead += n;
                    remaining -= n;
                }
            } catch (Exception e) {
                throw new RuntimeException("getObjectRead failed at offset=" + offset, e);
            }
            offset += len;
        }
        bh.consume(totalRead);
        return totalRead;
    }

    /**
     * Sequential pread: read the entire file in rangeSize chunks using pread(offset, buf).
     *
     * <p>This matches CachingBlockManager's read pattern:
     * <pre>
     *   ByteBuffer buffer = bufferPool.acquire(blockNumber);
     *   reader.read(buffer, offset, size, objectAttributes);  // one Range GET
     * </pre>
     *
     * <p>Key difference from {@link #getObjectRead}: pread reads directly into the caller's
     * buffer, avoiding the intermediate byte[] allocation on the JNI backend.
     */
    @Benchmark
    public long preadSequential(Blackhole bh) {
        String k = nextKey();
        long totalRead = 0;
        byte[] buf = new byte[rangeSize];
        long offset = 0;
        while (offset < fileSizeBytes) {
            int len = (int) Math.min(rangeSize, fileSizeBytes - offset);
            int n = client.pread(bucket, k, buf, offset, len);
            totalRead += n;
            offset += len;
        }
        bh.consume(totalRead);
        return totalRead;
    }

    /**
     * Random pread: simulate random seek + read pattern.
     *
     * <p>Reads {@code numRandomReads} chunks at pseudo-random offsets within the file.
     * The RNG is seeded once per trial (deterministic per trial, offsets vary across
     * invocations — avoids re-reading the same offsets every invocation).
     *
     * <p>Config via system properties:
     * <pre>
     *   -Doss.numRandomReads=10    (default 10)
     *   -Doss.randomSeed=42        (default 42)
     * </pre>
     */
    @Benchmark
    public long preadRandom(Blackhole bh) {
        String k = nextKey();
        long totalRead = 0;
        byte[] buf = new byte[rangeSize];
        for (int i = 0; i < numRandomReads; i++) {
            long maxOffset = Math.max(0, fileSizeBytes - rangeSize);
            long offset = (maxOffset > 0) ? (Math.abs(rng.nextLong()) % maxOffset) : 0;
            int len = (int) Math.min(rangeSize, fileSizeBytes - offset);
            int n = client.pread(bucket, k, buf, offset, len);
            totalRead += n;
        }
        bh.consume(totalRead);
        return totalRead;
    }

    // ── Main (JMH runner) ──

    public static void main(String[] args) throws Exception {
        String resultFile = System.getProperty("oss.resultFile", "/tmp/jmh-result.json");
        boolean quick = Boolean.getBoolean("oss.quick");

        org.openjdk.jmh.runner.options.ChainedOptionsBuilder builder = new OptionsBuilder()
                .include(OssBackendBenchmark.class.getSimpleName())
                .addProfiler(GCProfiler.class)
                .resultFormat(ResultFormatType.JSON)
                .result(resultFile);

        // Optional param filters (comma-separated), e.g. -Doss.fileSizes=64kb,4mb -Doss.backends=jni
        String fileSizes = System.getProperty("oss.fileSizes", "");
        if (!fileSizes.isEmpty()) {
            builder.param("fileSize", fileSizes.split(","));
            System.err.println("[bench] fileSize filter: " + fileSizes);
        }
        String backends = System.getProperty("oss.backends", "");
        if (!backends.isEmpty()) {
            builder.param("backend", backends.split(","));
            System.err.println("[bench] backend filter: " + backends);
        }

        if (quick) {
            // Quick mode: minimal iterations for fast feedback (~5 min total)
            builder.warmupIterations(1)
                   .measurementIterations(1)
                   .warmupTime(org.openjdk.jmh.runner.options.TimeValue.seconds(2))
                   .measurementTime(org.openjdk.jmh.runner.options.TimeValue.seconds(2));
            System.err.println("[bench] QUICK mode: 1 warmup + 1 measurement × 2s");
        }

        new Runner(builder.build()).run();
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

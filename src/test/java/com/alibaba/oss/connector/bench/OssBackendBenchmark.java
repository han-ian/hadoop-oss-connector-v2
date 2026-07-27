package com.alibaba.oss.connector.bench;

import com.alibaba.oss.connector.*;
import com.alibaba.oss.connector.models.ObjectSummary;

import java.io.InputStream;
import java.util.Arrays;
import java.util.List;

/**
 * Benchmark comparing JNI backend vs Java SDK backend.
 *
 * <p>Usage:
 * <pre>
 * java -Djava.library.path=/path/to/native \
 *      -cp target/hadoop-oss-3.3.5-2.0.26-alpha.jar:target/dependency/* \
 *      com.alibaba.oss.connector.bench.OssBackendBenchmark \
 *      --endpoint oss-cn-hangzhou-internal.aliyuncs.com \
 *      --bucket my-bucket \
 *      --key test-data/128mb.bin \
 *      --ak ACCESS_KEY_ID \
 *      --sk ACCESS_KEY_SECRET \
 *      --region cn-hangzhou
 * </pre>
 */
public class OssBackendBenchmark {

    // ── Config ──

    private String endpoint;
    private String bucket;
    private String key;
    private String accessKeyId;
    private String accessKeySecret;
    private String region;
    private String backendFilter = "all"; // "all", "jni", or "java"

    // ── Benchmark parameters ──

    private static final int WARMUP_ITERATIONS = 5;
    private static final int MEASURE_ITERATIONS = 20;
    private static final long[] READ_SIZES = {4096, 65536, 1048576, 4194304}; // 4KB, 64KB, 1MB, 4MB

    public static void main(String[] args) throws Exception {
        OssBackendBenchmark bench = new OssBackendBenchmark();
        bench.parseArgs(args);
        bench.run();
    }

    private void parseArgs(String[] args) {
        // AK/SK 默认从环境变量读取 (source dadi.env 后自动可用)
        accessKeyId = System.getenv("OSS_ACCESS_KEY_ID");
        accessKeySecret = System.getenv("OSS_ACCESS_KEY_SECRET");
        for (int i = 0; i < args.length; i += 2) {
            switch (args[i]) {
                case "--endpoint": endpoint = args[i + 1]; break;
                case "--bucket":   bucket = args[i + 1]; break;
                case "--key":      key = args[i + 1]; break;
                case "--ak":       accessKeyId = args[i + 1]; break;
                case "--sk":       accessKeySecret = args[i + 1]; break;
                case "--region":   region = args[i + 1]; break;
                case "--backend":  backendFilter = args[i + 1]; break;
            }
        }
        if (endpoint == null || bucket == null || key == null
                || accessKeyId == null || accessKeySecret == null) {
            System.err.println("Usage: OssBackendBenchmark --endpoint <ep> --bucket <b> --key <k> [--region <r>] [--backend all|jni|java]");
            System.err.println("  AK/SK: 从环境变量 OSS_ACCESS_KEY_ID / OSS_ACCESS_KEY_SECRET 读取, 或用 --ak/--sk 覆盖");
            System.exit(1);
        }
    }

    private void run() throws Exception {
        System.out.println("╔════════════════════════════════════════════════════════════╗");
        System.out.println("║     OSS Backend Benchmark: JNI vs Java SDK               ║");
        System.out.println("╚════════════════════════════════════════════════════════════╝");
        System.out.printf("Endpoint: %s%n", endpoint);
        System.out.printf("Bucket:   %s%n", bucket);
        System.out.printf("Key:      %s%n", key);
        System.out.printf("Warmup:   %d iterations%n", WARMUP_ITERATIONS);
        System.out.printf("Measure:  %d iterations%n", MEASURE_ITERATIONS);
        System.out.printf("Backend:  %s%n", backendFilter);
        System.out.println();

        // ── Test 1: headObject ──
        benchHeadObject();

        // ── Test 2: getObject (various sizes) ──
        for (long size : READ_SIZES) {
            benchGetObject(size);
        }

        // ── Test 3: listObjects ──
        benchListObjects();

        // ── Test 4: multi-threaded getObject ──
        benchMultiThreaded(4);
        benchMultiThreaded(8);
        benchMultiThreaded(16);

        System.out.println();
        System.out.println("Benchmark complete.");
    }

    // ── headObject benchmark ──

    private void benchHeadObject() throws Exception {
        System.out.println("── headObject ──");
        printHeader();

        for (String backendType : backends()) {
            try (OssBackend backend = createBackend(backendType)) {
                // warmup
                for (int i = 0; i < WARMUP_ITERATIONS; i++) {
                    backend.headObject(bucket, key);
                }
                // measure
                long[] latencies = new long[MEASURE_ITERATIONS];
                for (int i = 0; i < MEASURE_ITERATIONS; i++) {
                    long start = System.nanoTime();
                    long size = backend.headObject(bucket, key);
                    latencies[i] = System.nanoTime() - start;
                    if (i == 0) {
                        System.out.printf("  [%s] content-length = %d bytes%n", backend.backendName(), size);
                    }
                }
                printStats(backend.backendName(), latencies);
            }
        }
        System.out.println();
    }

    // ── getObject benchmark ──

    private void benchGetObject(long readSize) throws Exception {
        System.out.printf("── getObject (range size = %s) ──%n", formatSize(readSize));
        printHeader();

        for (String backendType : backends()) {
            try (OssBackend backend = createBackend(backendType)) {
                // warmup
                for (int i = 0; i < WARMUP_ITERATIONS; i++) {
                    readAndDiscard(backend.getObject(bucket, key, 0, readSize));
                }
                // measure
                long[] latencies = new long[MEASURE_ITERATIONS];
                long[] bytesRead = new long[MEASURE_ITERATIONS];
                for (int i = 0; i < MEASURE_ITERATIONS; i++) {
                    long start = System.nanoTime();
                    try (InputStream is = backend.getObject(bucket, key, 0, readSize)) {
                        bytesRead[i] = readAndDiscard(is);
                    }
                    latencies[i] = System.nanoTime() - start;
                }
                printStatsWithThroughput(backend.backendName(), latencies, readSize);
            }
        }
        System.out.println();
    }

    // ── listObjects benchmark ──

    private void benchListObjects() throws Exception {
        System.out.println("── listObjects ──");
        printHeader();

        for (String backendType : backends()) {
            try (OssBackend backend = createBackend(backendType)) {
                String prefix = key.contains("/") ? key.substring(0, key.lastIndexOf('/') + 1) : "";

                // warmup
                for (int i = 0; i < WARMUP_ITERATIONS; i++) {
                    List<ObjectSummary> contents = backend.listObjects(bucket, prefix);
                }
                // measure
                long[] latencies = new long[MEASURE_ITERATIONS];
                int keyCount = 0;
                for (int i = 0; i < MEASURE_ITERATIONS; i++) {
                    long start = System.nanoTime();
                    List<ObjectSummary> contents = backend.listObjects(bucket, prefix);
                    keyCount = contents.size();
                    latencies[i] = System.nanoTime() - start;
                }
                printStats(backend.backendName(), latencies);
                System.out.printf("  [%s] listed %d keys%n", backend.backendName(), keyCount);
            }
        }
        System.out.println();
    }

    // ── multi-threaded getObject ──

    private void benchMultiThreaded(int threadCount) throws Exception {
        System.out.printf("── multi-threaded getObject (%d threads, 1MB each) ──%n", threadCount);
        printHeader();

        for (String backendType : backends()) {
            try (OssBackend backend = createBackend(backendType)) {
                final long readSize = 1048576; // 1MB
                final int itersPerThread = MEASURE_ITERATIONS / threadCount;

                // warmup
                Thread[] warmThreads = new Thread[threadCount];
                for (int t = 0; t < threadCount; t++) {
                    warmThreads[t] = new Thread(() -> {
                        for (int i = 0; i < WARMUP_ITERATIONS; i++) {
                            readAndDiscard(backend.getObject(bucket, key, 0, readSize));
                        }
                    });
                    warmThreads[t].start();
                }
                for (Thread th : warmThreads) th.join();

                // measure
                long[] threadLatencies = new long[threadCount];
                long wallStart = System.nanoTime();
                Thread[] threads = new Thread[threadCount];
                for (int t = 0; t < threadCount; t++) {
                    final int tid = t;
                    threads[t] = new Thread(() -> {
                        long minLat = Long.MAX_VALUE;
                        for (int i = 0; i < itersPerThread; i++) {
                            long start = System.nanoTime();
                            readAndDiscard(backend.getObject(bucket, key, 0, readSize));
                            long lat = System.nanoTime() - start;
                            minLat = Math.min(minLat, lat);
                        }
                        threadLatencies[tid] = minLat;
                    });
                    threads[t].start();
                }
                for (Thread th : threads) th.join();
                long wallTime = System.nanoTime() - wallStart;

                double totalMB = (double) readSize * threadCount * itersPerThread / 1048576;
                double wallSec = wallTime / 1e9;
                double throughput = totalMB / wallSec;

                System.out.printf("  [%s] wall=%.2fs, throughput=%.1f MB/s, min_latency=%.1f ms%n",
                        backend.backendName(), wallSec, throughput,
                        Arrays.stream(threadLatencies).min().orElse(0) / 1e6);
            }
        }
        System.out.println();
    }

    // ── Helpers ──

    private String[] backends() {
        switch (backendFilter) {
            case "jni":  return new String[]{"jni"};
            case "java": return new String[]{"java"};
            default:     return new String[]{"jni", "java"};
        }
    }

    private OssBackend createBackend(String type) {
        switch (type) {
            case "jni":
                return new JniOssBackend(endpoint, null, null, null, region);
            case "java":
                return new JavaSdkOssBackend(endpoint, accessKeyId, accessKeySecret, region, bucket);
            default:
                throw new IllegalArgumentException("Unknown backend: " + type);
        }
    }

    private long readAndDiscard(InputStream is) {
        try {
            byte[] buf = new byte[8192];
            long total = 0;
            int n;
            while ((n = is.read(buf)) > 0) {
                total += n;
            }
            return total;
        } catch (Exception e) {
            throw new RuntimeException(e);
        }
    }

    private void printHeader() {
        System.out.printf("  %-35s %10s %10s %10s %10s%n",
                "Backend", "P50(ms)", "P99(ms)", "Min(ms)", "Max(ms)");
        System.out.printf("  %-35s %10s %10s %10s %10s%n",
                "───────", "───────", "───────", "───────", "───────");
    }

    private void printStats(String name, long[] latenciesNanos) {
        Arrays.sort(latenciesNanos);
        int n = latenciesNanos.length;
        double p50 = latenciesNanos[n / 2] / 1e6;
        double p99 = latenciesNanos[(int)(n * 0.99)] / 1e6;
        double min = latenciesNanos[0] / 1e6;
        double max = latenciesNanos[n - 1] / 1e6;
        System.out.printf("  %-35s %10.2f %10.2f %10.2f %10.2f%n",
                name, p50, p99, min, max);
    }

    private void printStatsWithThroughput(String name, long[] latenciesNanos, long readSize) {
        printStats(name, latenciesNanos);
        Arrays.sort(latenciesNanos);
        double avgSec = Arrays.stream(latenciesNanos).average().orElse(1) / 1e9;
        double mbps = (readSize / 1048576.0) / avgSec;
        System.out.printf("  %-35s avg throughput: %.1f MB/s%n", name, mbps);
    }

    private String formatSize(long bytes) {
        if (bytes >= 1048576) return (bytes / 1048576) + "MB";
        if (bytes >= 1024) return (bytes / 1024) + "KB";
        return bytes + "B";
    }
}

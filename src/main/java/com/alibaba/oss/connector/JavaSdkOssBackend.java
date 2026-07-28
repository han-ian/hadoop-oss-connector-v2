package com.alibaba.oss.connector;

import com.aliyun.sdk.service.oss2.ClientConfiguration;
import com.aliyun.sdk.service.oss2.DefaultOSSDualClient;
import com.aliyun.sdk.service.oss2.OSSDualClient;
import com.aliyun.sdk.service.oss2.OperationOptions;
import com.aliyun.sdk.service.oss2.credentials.StaticCredentialsProvider;
import com.aliyun.sdk.service.oss2.models.*;
import com.aliyun.sdk.service.oss2.transport.BinaryData;

import java.io.ByteArrayInputStream;
import java.io.InputStream;
import java.util.ArrayList;
import java.util.List;

/**
 * Java SDK backend — uses alibabacloud-oss-v2 Java SDK (OSSDualClient).
 *
 * <p>This is the baseline backend for performance comparison against JNI.
 * All SDK model types are fully-qualified to avoid collisions with
 * {@code com.alibaba.oss.connector} types.
 */
public class JavaSdkOssBackend implements OssBackend {

    private final OSSDualClient client;
    private final String defaultBucket;

    /**
     * Create with an existing OSSDualClient.
     */
    public JavaSdkOssBackend(OSSDualClient client, String defaultBucket) {
        this.client = client;
        this.defaultBucket = defaultBucket;
    }

    /**
     * Create from endpoint + AK/SK.
     */
    public JavaSdkOssBackend(String endpoint, String accessKeyId, String accessKeySecret,
                              String region, String defaultBucket) {
        StaticCredentialsProvider credProvider =
                new StaticCredentialsProvider(accessKeyId, accessKeySecret);
        ClientConfiguration clientConf = ClientConfiguration.newBuilder()
                .endpoint(endpoint)
                .region(region != null ? region : "")
                .credentialsProvider(credProvider)
                .build();
        this.client = new DefaultOSSDualClient(clientConf);
        this.defaultBucket = defaultBucket;
    }

    @Override
    public InputStream getObject(String bucket, String key, long offset, long length) {
        String range = "bytes=" + offset + "-" + (offset + length - 1);
        try {
            // Fully qualified to avoid collision with com.alibaba.oss.connector.GetObjectResult
            com.aliyun.sdk.service.oss2.models.GetObjectResult sdkResult = client.getObject(
                    GetObjectRequest.newBuilder()
                            .bucket(bucket)
                            .key(key)
                            .range(range)
                            .build(),
                    OperationOptions.defaults());
            return sdkResult.body(); // returns InputStream
        } catch (Exception e) {
            throw new RuntimeException("getObject failed: " + bucket + "/" + key, e);
        }
    }

    @Override
    public int pread(String bucket, String key, byte[] buf, long offset, int length) {
        String range = "bytes=" + offset + "-" + (offset + length - 1);
        try {
            com.aliyun.sdk.service.oss2.models.GetObjectResult sdkResult = client.getObject(
                    com.aliyun.sdk.service.oss2.models.GetObjectRequest.newBuilder()
                            .bucket(bucket)
                            .key(key)
                            .range(range)
                            .build(),
                    com.aliyun.sdk.service.oss2.OperationOptions.defaults());
            try (InputStream is = sdkResult.body()) {
                int totalRead = 0;
                while (totalRead < length) {
                    int n = is.read(buf, totalRead, length - totalRead);
                    if (n <= 0) break;
                    totalRead += n;
                }
                if (totalRead != length) {
                    throw new IllegalStateException("pread short read: " + bucket + "/" + key
                            + " offset=" + offset + " length=" + length + " read=" + totalRead);
                }
                return totalRead;
            }
        } catch (Exception e) {
            throw new RuntimeException("pread failed: " + bucket + "/" + key, e);
        }
    }

    @Override
    public long headObject(String bucket, String key) {
        try {
            GetObjectMetaResult result = client.getObjectMeta(
                    GetObjectMetaRequest.newBuilder()
                            .bucket(bucket)
                            .key(key)
                            .build());
            return result.contentLength();
        } catch (Exception e) {
            throw new RuntimeException("headObject failed: " + bucket + "/" + key, e);
        }
    }

    @Override
    public List<com.alibaba.oss.connector.models.ObjectSummary> listObjects(String bucket, String prefix) {
        try {
            ListObjectsV2Request req = ListObjectsV2Request.newBuilder()
                    .bucket(bucket)
                    .prefix(prefix)
                    .build();
            ListObjectsV2Result sdkResult = client.listObjectsV2(req);

            List<com.alibaba.oss.connector.models.ObjectSummary> summaries = new ArrayList<>();
            for (ObjectSummary s : sdkResult.contents()) {
                summaries.add(new com.alibaba.oss.connector.models.ObjectSummary(
                        s.key(), s.size(), ""));
            }
            return summaries;
        } catch (Exception e) {
            throw new RuntimeException("listObjects failed: " + bucket + "/" + prefix, e);
        }
    }

    @Override
    public void deleteObject(String bucket, String key) {
        try {
            client.deleteObject(
                    DeleteObjectRequest.newBuilder()
                            .bucket(bucket)
                            .key(key)
                            .build());
        } catch (Exception e) {
            throw new RuntimeException("deleteObject failed: " + bucket + "/" + key, e);
        }
    }

    @Override
    public String putObject(String bucket, String key, byte[] data) {
        try {
            // Fully qualified to avoid collision with com.alibaba.oss.connector.PutObjectResult
            com.aliyun.sdk.service.oss2.models.PutObjectResult sdkResult = client.putObject(
                    PutObjectRequest.newBuilder()
                            .bucket(bucket)
                            .key(key)
                            .contentLength(data.length)
                            .body(BinaryData.fromStream(new ByteArrayInputStream(data)))
                            .build());
            return sdkResult.eTag();
        } catch (Exception e) {
            throw new RuntimeException("putObject failed: " + bucket + "/" + key, e);
        }
    }

    @Override
    public String backendName() {
        return "Java SDK (alibabacloud-oss-v2)";
    }

    @Override
    public void close() {
        try {
            client.close();
        } catch (Exception e) {
            throw new RuntimeException("close failed", e);
        }
    }
}

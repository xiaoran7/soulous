package com.soulous.storage;

import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.beans.factory.DisposableBean;
import software.amazon.awssdk.auth.credentials.AwsBasicCredentials;
import software.amazon.awssdk.auth.credentials.StaticCredentialsProvider;
import software.amazon.awssdk.core.ResponseInputStream;
import software.amazon.awssdk.core.sync.RequestBody;
import software.amazon.awssdk.http.urlconnection.UrlConnectionHttpClient;
import software.amazon.awssdk.regions.Region;
import software.amazon.awssdk.services.s3.S3Client;
import software.amazon.awssdk.services.s3.S3Configuration;
import software.amazon.awssdk.services.s3.model.DeleteObjectRequest;
import software.amazon.awssdk.services.s3.model.GetObjectRequest;
import software.amazon.awssdk.services.s3.model.GetObjectResponse;
import software.amazon.awssdk.services.s3.model.ListObjectsV2Request;
import software.amazon.awssdk.services.s3.model.NoSuchKeyException;
import software.amazon.awssdk.services.s3.model.PutObjectRequest;

import java.io.IOException;
import java.net.URI;
import java.time.Instant;
import java.util.ArrayList;
import java.util.List;
import java.util.Optional;

/**
 * 【S3 兼容对象存储实现。支持 AWS S3 及任何 S3 兼容服务（如 MinIO、阿里云 OSS）。
 * 通过 pathStyleAccess 配置支持路径风格访问（适用于自建 MinIO 等场景）。
 * 实现 DisposableBean 接口，在 Spring 容器关闭时自动释放 S3 客户端资源。】
 */
public class S3ObjectStorage implements ObjectStorage, DisposableBean {
    private static final Logger log = LoggerFactory.getLogger(S3ObjectStorage.class);

    /** AWS S3 客户端实例 */
    private final S3Client client;
    /** S3 存储桶名称 */
    private final String bucket;

    /**
     * 【构造器：根据配置参数初始化 S3 客户端。验证 bucket、accessKey、secretKey 不可为空。】
     *
     * @param endpoint   【S3 端点地址，为空时使用 AWS 默认端点】
     * @param region     【S3 区域，默认 us-east-1】
     * @param bucket     【存储桶名称，必填】
     * @param accessKey  【访问密钥 ID，必填】
     * @param secretKey  【访问密钥密文，必填】
     * @param pathStyle  【是否启用路径风格访问，适用于 MinIO 等非 AWS S3 服务】
     * @throws IllegalStateException 【bucket 或密钥为空时抛出】
     */
    S3ObjectStorage(String endpoint, String region, String bucket, String accessKey, String secretKey, boolean pathStyle) {
        if (bucket == null || bucket.isBlank()) throw new IllegalStateException("soulous.storage.s3.bucket is required");
        if (accessKey == null || accessKey.isBlank() || secretKey == null || secretKey.isBlank()) {
            throw new IllegalStateException("soulous.storage.s3.access-key and secret-key are required");
        }
        var creds = StaticCredentialsProvider.create(AwsBasicCredentials.create(accessKey, secretKey));
        var builder = S3Client.builder()
                .credentialsProvider(creds)
                .region(Region.of(region == null || region.isBlank() ? "us-east-1" : region))
                .httpClient(UrlConnectionHttpClient.create())
                .serviceConfiguration(S3Configuration.builder().pathStyleAccessEnabled(pathStyle).build());
        if (endpoint != null && !endpoint.isBlank()) {
            builder.endpointOverride(URI.create(endpoint));
        }
        this.client = builder.build();
        this.bucket = bucket;
        log.info("S3 storage initialized: endpoint={}, bucket={}, pathStyle={}", endpoint, bucket, pathStyle);
    }

    /**
     * 【存储对象到 S3。将字节数据以指定 content type 上传到指定 key。】
     *
     * @param key         【对象 key】
     * @param data        【对象字节数据】
     * @param contentType 【MIME 类型】
     * @throws IOException 【S3 操作失败时抛出】
     */
    @Override
    public void store(String key, byte[] data, String contentType) throws IOException {
        try {
            client.putObject(
                    PutObjectRequest.builder().bucket(bucket).key(key).contentType(contentType).build(),
                    RequestBody.fromBytes(data)
            );
        } catch (Exception ex) {
            throw new IOException("S3 putObject failed: " + ex.getMessage(), ex);
        }
    }

    /**
     * 【从 S3 加载对象。返回包含输入流、content type 和内容长度的 StoredObject。】
     *
     * @param key 【对象 key】
     * @return 【Optional 包装的 StoredObject，对象不存在时返回 empty】
     * @throws IOException 【S3 操作失败时抛出】
     */
    @Override
    public Optional<StoredObject> load(String key) throws IOException {
        try {
            ResponseInputStream<GetObjectResponse> stream = client.getObject(
                    GetObjectRequest.builder().bucket(bucket).key(key).build()
            );
            var meta = stream.response();
            return Optional.of(new StoredObject(stream, meta.contentType() == null ? "application/octet-stream" : meta.contentType(),
                    meta.contentLength() == null ? -1L : meta.contentLength()));
        } catch (NoSuchKeyException ex) {
            return Optional.empty();
        } catch (Exception ex) {
            throw new IOException("S3 getObject failed: " + ex.getMessage(), ex);
        }
    }

    /** 【返回存储后端名称标识 "s3"】 */
    @Override
    public String backendName() {
        return "s3";
    }

    /**
     * 【列出所有最后修改时间早于阈值的对象，支持分页遍历（处理 continuation token）。用于 GC 任务。】
     *
     * @param threshold 【时间阈值】
     * @return 【符合条件的对象信息列表】
     * @throws IOException 【S3 操作失败时抛出】
     */
    @Override
    public List<KeyInfo> listOlderThan(Instant threshold) throws IOException {
        try {
            var result = new ArrayList<KeyInfo>();
            String continuation = null;
            do {
                var builder = ListObjectsV2Request.builder().bucket(bucket);
                if (continuation != null) builder.continuationToken(continuation);
                var resp = client.listObjectsV2(builder.build());
                for (var obj : resp.contents()) {
                    if (obj.lastModified().isBefore(threshold)) {
                        result.add(new KeyInfo(obj.key(), obj.lastModified(), obj.size()));
                    }
                }
                continuation = Boolean.TRUE.equals(resp.isTruncated()) ? resp.nextContinuationToken() : null;
            } while (continuation != null);
            return result;
        } catch (Exception ex) {
            throw new IOException("S3 listObjects failed: " + ex.getMessage(), ex);
        }
    }

    /**
     * 【从 S3 删除指定 key 的对象】
     *
     * @param key 【对象 key】
     * @throws IOException 【S3 操作失败时抛出】
     */
    @Override
    public void delete(String key) throws IOException {
        try {
            client.deleteObject(DeleteObjectRequest.builder().bucket(bucket).key(key).build());
        } catch (Exception ex) {
            throw new IOException("S3 deleteObject failed: " + ex.getMessage(), ex);
        }
    }

    /** 【Bean 销毁回调：关闭 S3 客户端释放连接资源】 */
    @Override
    public void destroy() {
        try { client.close(); } catch (Exception ignored) {}
    }
}

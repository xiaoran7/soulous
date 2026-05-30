package com.soulous.storage;

import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;

import java.nio.file.Path;

/**
 * 【存储配置类：根据 soulous.storage.backend 配置项动态选择并创建对象存储 Bean。
 * 支持 "local"（本地文件系统）和 "s3"（S3 兼容服务）两种后端。】
 */
@Configuration
public class StorageConfig {
    private static final Logger log = LoggerFactory.getLogger(StorageConfig.class);

    /**
     * 【创建 ObjectStorage Bean。根据 backend 配置选择 LocalObjectStorage 或 S3ObjectStorage。】
     *
     * @param backend      【存储后端类型："local" 或 "s3"，默认 "local"】
     * @param uploadDir    【本地存储目录路径（仅 local 后端使用）】
     * @param s3Endpoint   【S3 端点地址（为空时使用 AWS 默认）】
     * @param s3Region     【S3 区域，默认 us-east-1】
     * @param s3Bucket     【S3 存储桶名称】
     * @param s3AccessKey  【S3 访问密钥 ID】
     * @param s3SecretKey  【S3 访问密钥密文】
     * @param s3PathStyle  【是否启用路径风格访问，默认 true】
     * @return 【ObjectStorage 实例】
     */
    @Bean
    ObjectStorage objectStorage(
            @Value("${soulous.storage.backend:local}") String backend,
            @Value("${soulous.upload-dir}") String uploadDir,
            @Value("${soulous.storage.s3.endpoint:}") String s3Endpoint,
            @Value("${soulous.storage.s3.region:us-east-1}") String s3Region,
            @Value("${soulous.storage.s3.bucket:}") String s3Bucket,
            @Value("${soulous.storage.s3.access-key:}") String s3AccessKey,
            @Value("${soulous.storage.s3.secret-key:}") String s3SecretKey,
            @Value("${soulous.storage.s3.path-style-access:true}") boolean s3PathStyle) {
        var picked = backend == null ? "local" : backend.trim().toLowerCase();
        if ("s3".equals(picked)) {
            log.info("ObjectStorage backend = s3 (endpoint={}, bucket={})", s3Endpoint.isBlank() ? "AWS default" : s3Endpoint, s3Bucket);
            return new S3ObjectStorage(s3Endpoint, s3Region, s3Bucket, s3AccessKey, s3SecretKey, s3PathStyle);
        }
        log.info("ObjectStorage backend = local (dir={})", uploadDir);
        return new LocalObjectStorage(Path.of(uploadDir));
    }
}

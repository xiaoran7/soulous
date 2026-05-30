package com.soulous.storage;

import java.io.ByteArrayInputStream;
import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.time.Instant;
import java.util.ArrayList;
import java.util.List;
import java.util.Optional;
import java.util.stream.Stream;

/**
 * 【本地文件系统对象存储实现。将对象存储在服务器本地磁盘目录中。
 * 使用路径遍历防护（normalize + startsWith 校验）确保 key 不会逃逸出根目录。
 * 适用于开发环境和小规模部署场景。】
 */
public class LocalObjectStorage implements ObjectStorage {
    /** 存储根目录的绝对路径 */
    private final Path root;

    /**
     * 【构造器：设置存储根目录，自动转换为绝对路径并规范化】
     *
     * @param root 【存储根目录路径】
     */
    public LocalObjectStorage(Path root) {
        this.root = root.toAbsolutePath().normalize();
    }

    /**
     * 【存储对象到本地文件系统。自动创建目录，校验 key 不包含路径遍历攻击。】
     *
     * @param key         【文件名 key】
     * @param data        【文件字节数据】
     * @param contentType 【MIME 类型（本地存储暂未使用，但保持接口一致性）】
     * @throws IOException 【key 非法或写入失败时抛出】
     */
    @Override
    public void store(String key, byte[] data, String contentType) throws IOException {
        Files.createDirectories(root);
        var target = root.resolve(key).normalize();
        if (!target.startsWith(root)) {
            throw new IOException("Invalid key: " + key);
        }
        Files.write(target, data);
    }

    /**
     * 【从本地文件系统加载对象。校验路径安全性后读取文件内容。】
     *
     * @param key 【文件名 key】
     * @return 【Optional 包装的 StoredObject，文件不存在或路径非法时返回 empty】
     * @throws IOException 【读取失败时抛出】
     */
    @Override
    public Optional<StoredObject> load(String key) throws IOException {
        var target = root.resolve(key).normalize();
        if (!target.startsWith(root) || !Files.exists(target) || !Files.isRegularFile(target)) {
            return Optional.empty();
        }
        var bytes = Files.readAllBytes(target);
        return Optional.of(new StoredObject(new ByteArrayInputStream(bytes), guessContentType(key), bytes.length));
    }

    /** 【返回存储后端名称标识 "local"】 */
    @Override
    public String backendName() {
        return "local";
    }

    /**
     * 【列出本地文件系统中最后修改时间早于阈值的文件，用于 GC 任务。】
     *
     * @param threshold 【时间阈值】
     * @return 【符合条件的文件信息列表】
     * @throws IOException 【遍历目录失败时抛出】
     */
    @Override
    public List<KeyInfo> listOlderThan(Instant threshold) throws IOException {
        if (!Files.exists(root)) return List.of();
        var result = new ArrayList<KeyInfo>();
        try (Stream<Path> stream = Files.list(root)) {
            stream.filter(Files::isRegularFile).forEach(p -> {
                try {
                    var mtime = Files.getLastModifiedTime(p).toInstant();
                    if (mtime.isBefore(threshold)) {
                        result.add(new KeyInfo(p.getFileName().toString(), mtime, Files.size(p)));
                    }
                } catch (IOException ignored) {
                    // skip — best-effort scan
                }
            });
        }
        return result;
    }

    /**
     * 【删除本地文件系统中的对象。校验路径安全性后删除文件，文件不存在时静默处理。】
     *
     * @param key 【文件名 key】
     * @throws IOException 【删除失败时抛出】
     */
    @Override
    public void delete(String key) throws IOException {
        var target = root.resolve(key).normalize();
        if (!target.startsWith(root)) return;
        Files.deleteIfExists(target);
    }

    /**
     * 【根据文件扩展名猜测 MIME 类型，支持常见图片格式，未知格式返回 application/octet-stream】
     *
     * @param key 【文件名】
     * @return 【猜测的 MIME 类型】
     */
    private static String guessContentType(String key) {
        var lower = key.toLowerCase();
        if (lower.endsWith(".png")) return "image/png";
        if (lower.endsWith(".jpg") || lower.endsWith(".jpeg")) return "image/jpeg";
        if (lower.endsWith(".gif")) return "image/gif";
        if (lower.endsWith(".webp")) return "image/webp";
        return "application/octet-stream";
    }
}

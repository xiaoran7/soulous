package com.soulous.storage;

import java.io.IOException;
import java.io.InputStream;
import java.time.Instant;
import java.util.List;
import java.util.Optional;

/**
 * 【可插拔对象存储接口。通过 soulous.storage.backend=local|s3 配置选择后端。
 * 两种后端共享相同的 key 格式（UUID + 扩展名），因此返回给客户端的 URL
 * （/uploads/<key>）在切换后端时保持稳定。】
 *
 * <p>Pluggable object storage. Set soulous.storage.backend=local|s3.
 * Both backends share the same key format (UUID + ext) so URLs returned to
 * clients (`/uploads/<key>`) are stable across backends.</p>
 */
public interface ObjectStorage {
    /**
     * 【持久化字节数据到指定 key 下。调用方负责验证内容合法性。】
     *
     * Persist bytes under the given key. Caller validates content.
     *
     * @param key         【对象 key，格式为 UUID + 扩展名】
     * @param data        【对象字节数据】
     * @param contentType 【MIME 类型】
     * @throws IOException 【存储操作失败时抛出】
     */
    void store(String key, byte[] data, String contentType) throws IOException;

    /**
     * 【根据 key 加载对象。对象不存在时返回 empty。】
     *
     * Fetch the object back. Returns empty if missing.
     *
     * @param key 【对象 key】
     * @return 【Optional 包装的 StoredObject】
     * @throws IOException 【加载操作失败时抛出】
     */
    Optional<StoredObject> load(String key) throws IOException;

    /**
     * 【返回存储后端名称标识（如 "local"、"s3"），用于日志和监控。】
     *
     * @return 【后端名称】
     */
    String backendName();

    /**
     * 【列出所有最后修改时间严格早于阈值的对象。用于 GC 任务识别孤儿对象。】
     *
     * All keys with a last-modified time strictly before {@code threshold}. Used by GC.
     *
     * @param threshold 【时间阈值】
     * @return 【符合条件的对象信息列表】
     * @throws IOException 【列举操作失败时抛出】
     */
    List<KeyInfo> listOlderThan(Instant threshold) throws IOException;

    /**
     * 【根据 key 删除对象。对象不存在时为空操作。】
     *
     * Remove an object by key. No-op if missing.
     *
     * @param key 【对象 key】
     * @throws IOException 【删除操作失败时抛出】
     */
    void delete(String key) throws IOException;

    /** 【存储对象记录：包含输入流、MIME 类型和内容长度】 */
    record StoredObject(InputStream content, String contentType, long contentLength) {}

    /** 【对象元信息记录：包含 key、最后修改时间和文件大小】 */
    record KeyInfo(String key, Instant lastModified, long size) {}
}

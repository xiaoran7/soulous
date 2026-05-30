package com.soulous;

import com.soulous.storage.LocalObjectStorage;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

import java.nio.file.Path;

import static org.junit.jupiter.api.Assertions.*;

/**
 * 【本地对象存储测试类：验证 LocalObjectStorage 的文件存取功能，
 * 包括存取往返一致性（store→load）、缺失文件返回空、路径穿越攻击拦截、
 * 以及基于文件扩展名的 content-type 推断。
 * 使用 JUnit 5 的 @TempDir 注解提供隔离的临时目录。】
 */
class LocalObjectStorageTests {

    /**
     * 【测试场景：存储文件后通过 key 加载，验证 content-type、内容长度和字节内容完全一致，
     * 且 backendName 返回"local"。】
     */
    @Test
    void roundTripStoresAndLoadsBytes(@TempDir Path tmp) throws Exception {
        var storage = new LocalObjectStorage(tmp);
        var key = "abc-123.png";
        byte[] data = new byte[] {1, 2, 3, 4, 5};

        storage.store(key, data, "image/png");
        var loaded = storage.load(key).orElseThrow();

        assertEquals("image/png", loaded.contentType());
        assertEquals(5, loaded.contentLength());
        assertArrayEquals(data, loaded.content().readAllBytes());
        assertEquals("local", storage.backendName());
    }

    /**
     * 【测试场景：加载不存在的 key 时应返回空 Optional，而非抛出异常。】
     */
    @Test
    void loadMissingKeyReturnsEmpty(@TempDir Path tmp) throws Exception {
        var storage = new LocalObjectStorage(tmp);
        assertTrue(storage.load("nope.png").isEmpty());
    }

    /**
     * 【测试场景：包含路径穿越字符（如"../escape.png"）的 key 应被拒绝，
     * 抛出 IOException 且消息中包含"Invalid"，防止目录遍历攻击。】
     */
    @Test
    void rejectsTraversalKey(@TempDir Path tmp) {
        var storage = new LocalObjectStorage(tmp);
        var ex = assertThrows(java.io.IOException.class,
                () -> storage.store("../escape.png", new byte[]{0}, "image/png"));
        assertTrue(ex.getMessage().contains("Invalid"));
    }

    /**
     * 【测试场景：不同文件扩展名（.jpg、.webp）存储后加载时，
     * content-type 应正确回显为存储时指定的 MIME 类型，验证扩展名映射逻辑。】
     */
    @Test
    void contentTypeFallsBackByExtension(@TempDir Path tmp) throws Exception {
        var storage = new LocalObjectStorage(tmp);
        storage.store("a.jpg", new byte[]{0}, "image/jpeg");
        storage.store("b.webp", new byte[]{0}, "image/webp");
        assertEquals("image/jpeg", storage.load("a.jpg").orElseThrow().contentType());
        assertEquals("image/webp", storage.load("b.webp").orElseThrow().contentType());
    }
}

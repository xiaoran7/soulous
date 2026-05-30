package com.soulous.storage;

import com.soulous.common.exception.BadRequestException;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.stereotype.Service;
import org.springframework.web.multipart.MultipartFile;

import java.io.IOException;
import java.util.Locale;
import java.util.Map;
import java.util.Optional;
import java.util.Set;
import java.util.UUID;

/**
 * 【文件存储服务：处理图片上传的核心业务逻辑，包括文件验证、压缩、存储和加载。
 * 支持 JPEG、PNG、GIF、WebP 格式，文件大小受 maxBytes 限制。
 * 上传前自动进行图片压缩以节省存储空间。】
 */
@Service
public class FileStorageService {
    /** 允许的 MIME 类型与对应扩展名的映射 */
    private static final Map<String, String> ALLOWED = Map.of(
            "image/jpeg", ".jpg",
            "image/jpg", ".jpg",
            "image/png", ".png",
            "image/gif", ".gif",
            "image/webp", ".webp"
    );
    /** 允许的文件扩展名集合 */
    private static final Set<String> ALLOWED_EXT = Set.of(".jpg", ".jpeg", ".png", ".gif", ".webp");

    /** 对象存储后端 */
    private final ObjectStorage storage;
    /** 最大允许上传文件大小（字节） */
    private final long maxBytes;

    /**
     * 【构造器：注入存储后端和最大文件大小配置】
     *
     * @param storage    【对象存储实现】
     * @param maxFileMb 【最大文件大小（MB），通过 soulous.storage.max-file-mb 配置，默认 20】
     */
    FileStorageService(ObjectStorage storage,
                       @Value("${soulous.storage.max-file-mb:20}") int maxFileMb) {
        this.storage = storage;
        this.maxBytes = Math.max(1, maxFileMb) * 1024L * 1024L;
    }

    /**
     * 【存储图片的便捷方法，委托给 storeScreenshot】
     *
     * @param file 【上传的图片文件】
     * @return 【存储后的访问 URL，格式为 /uploads/<key>】
     */
    public String storeImage(MultipartFile file) {
        return storeScreenshot(file);
    }

    /**
     * 【存储截图/凭证图片。执行完整的验证流程：空文件检查 → 大小限制 → MIME 类型校验
     * → 扩展名校验 → 压缩 → 生成 UUID key 并存储。】
     *
     * @param file 【上传的文件】
     * @return 【存储后的访问 URL，格式为 /uploads/<UUID>.<ext>】
     * @throws BadRequestException 【文件为空、过大、格式不支持或存储失败时抛出】
     */
    public String storeScreenshot(MultipartFile file) {
        if (file == null || file.isEmpty()) {
            throw new BadRequestException("凭证文件为空");
        }
        if (file.getSize() > maxBytes) {
            throw new BadRequestException("凭证图片过大（最大 " + (maxBytes / 1024 / 1024) + "MB）");
        }
        var contentType = file.getContentType() == null ? "" : file.getContentType().toLowerCase(Locale.ROOT);
        if (!ALLOWED.containsKey(contentType)) {
            throw new BadRequestException("仅支持 JPEG、PNG、GIF、WebP 图片");
        }
        var original = file.getOriginalFilename() == null ? "screenshot" : file.getOriginalFilename();
        var providedExt = extension(original).toLowerCase(Locale.ROOT);
        if (!providedExt.isEmpty() && !ALLOWED_EXT.contains(providedExt)) {
            throw new BadRequestException("不允许的文件扩展名");
        }
        try {
            var compressed = ImageCompressor.compress(file.getBytes(), contentType);
            // compressed.extension wins so the saved file matches the encoded format
            // 使用压缩后的扩展名，确保保存的文件与编码格式一致
            var key = UUID.randomUUID() + compressed.extension();
            storage.store(key, compressed.data(), compressed.contentType());
            return "/uploads/" + key;
        } catch (IOException ex) {
            throw new BadRequestException("存储凭证失败");
        }
    }

    /** 【用于 /uploads/{key} 控制器流式返回对象数据。校验 key 合法性后从存储后端加载。】
     *  Used by /uploads/{key} controller to stream object back. */
    public Optional<ObjectStorage.StoredObject> load(String key) {
        if (!isValidKey(key)) return Optional.empty();
        try {
            return storage.load(key);
        } catch (IOException ex) {
            return Optional.empty();
        }
    }

    /**
     * 【返回最大允许文件大小（字节）】
     *
     * @return 【最大字节数】
     */
    public long maxBytes() {
        return maxBytes;
    }

    /**
     * 【校验 key 格式是否合法：仅允许字母数字下划线连字符，1-80 字符，以合法图片扩展名结尾】
     *
     * @param key 【待校验的 key】
     * @return 【是否合法】
     */
    private static boolean isValidKey(String key) {
        return key != null && key.matches("[A-Za-z0-9_-]{1,80}\\.(png|jpg|jpeg|gif|webp)");
    }

    /**
     * 【从文件名中提取扩展名，不合法扩展名默认返回 .png】
     *
     * @param name 【文件名】
     * @return 【文件扩展名（含点号）】
     */
    private String extension(String name) {
        var dot = name.lastIndexOf('.');
        if (dot < 0 || dot == name.length() - 1) return ".png";
        var ext = name.substring(dot).toLowerCase(Locale.ROOT);
        return ext.matches("\\.(png|jpg|jpeg|webp|gif)") ? ext : ".png";
    }
}

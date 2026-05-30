package com.soulous.storage;

import com.soulous.auth.UserService;
import com.soulous.common.web.BaseController;
import jakarta.servlet.http.HttpServletRequest;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RequestParam;
import org.springframework.web.bind.annotation.RestController;
import org.springframework.web.multipart.MultipartFile;

import java.util.Map;

/**
 * 【文件上传控制器：提供截图/凭证图片的上传 API 接口。
 * 路径 /api/files/screenshots，需要用户认证。】
 */
@RestController
@RequestMapping("/api/files")
class FileController extends BaseController {
    /** 文件存储服务 */
    private final FileStorageService files;

    /**
     * 【构造器：注入依赖】
     *
     * @param users 【用户服务，用于认证】
     * @param files 【文件存储服务】
     */
    FileController(UserService users, FileStorageService files) {
        super(users);
        this.files = files;
    }

    /**
     * 【上传截图/凭证图片接口。校验用户认证后委托 FileStorageService 处理上传。】
     *
     * @param request 【HTTP 请求，用于获取当前用户】
     * @param file    【上传的文件，来自 multipart 表单字段 "file"】
     * @return 【包含文件访问 URL 的 Map，格式为 {"url": "/uploads/<key>"}】
     */
    @PostMapping("/screenshots")
    Object uploadScreenshot(HttpServletRequest request, @RequestParam("file") MultipartFile file) {
        current(request);
        return Map.of("url", files.storeScreenshot(file));
    }
}

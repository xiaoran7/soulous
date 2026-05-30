package com.soulous.storage;

import com.soulous.auth.UserService;
import com.soulous.common.web.BaseController;
import jakarta.servlet.http.HttpServletRequest;
import org.springframework.core.io.InputStreamResource;
import org.springframework.http.HttpHeaders;
import org.springframework.http.MediaType;
import org.springframework.http.ResponseEntity;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.RestController;

/**
 * 【文件流式返回控制器：处理 /uploads/{key} 请求，从对象存储加载文件并以流式方式返回给客户端。
 * 设置 Cache-Control: private, max-age=300 使浏览器缓存 5 分钟。
 * 需要认证（通过 JWT 过滤器），未认证请求不会到达此处。】
 */
@RestController
class UploadStreamController extends BaseController {
    /** 文件存储服务 */
    private final FileStorageService files;

    /**
     * 【构造器：注入依赖】
     *
     * @param users 【用户服务，用于认证】
     * @param files 【文件存储服务】
     */
    UploadStreamController(UserService users, FileStorageService files) {
        super(users);
        this.files = files;
    }

    /**
     * 【流式返回上传文件。校验用户认证后从存储后端加载文件，
     * 设置正确的 Content-Type 和缓存头，以 InputStreamResource 方式返回。】
     *
     * @param request 【HTTP 请求，用于获取当前用户】
     * @param key     【文件 key，从 URL 路径提取】
     * @return 【包含文件流的 ResponseEntity，文件不存在时返回 404】
     */
    @GetMapping("/uploads/{key:.+}")
    ResponseEntity<InputStreamResource> serve(HttpServletRequest request, @PathVariable String key) {
        current(request);
        var loaded = files.load(key);
        if (loaded.isEmpty()) {
            return ResponseEntity.notFound().build();
        }
        var obj = loaded.get();
        var builder = ResponseEntity.ok()
                .contentType(MediaType.parseMediaType(obj.contentType()))
                .header(HttpHeaders.CACHE_CONTROL, "private, max-age=300");
        if (obj.contentLength() >= 0) builder.contentLength(obj.contentLength());
        return builder.body(new InputStreamResource(obj.content()));
    }
}

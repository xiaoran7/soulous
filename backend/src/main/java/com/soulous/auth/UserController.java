package com.soulous.auth;

import com.soulous.common.web.BaseController;
import com.soulous.storage.FileStorageService;
import jakarta.servlet.http.HttpServletRequest;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.PutMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RequestParam;
import org.springframework.web.bind.annotation.RestController;
import org.springframework.web.multipart.MultipartFile;

/**
 * 【用户相关 REST API 控制器。
 *  处理当前登录用户的个人信息查看、更新以及头像上传等操作。
 *  所有端点均要求已认证的请求（通过 JWT 过滤器验证）。
 *  继承 {@link BaseController} 以复用用户身份解析等通用逻辑。】
 *
 * <p>User-related REST endpoints: view profile, update profile, upload avatar.</p>
 */
@RestController
@RequestMapping("/api/users")
class UserController extends BaseController {
    /** 【文件存储服务，负责将上传的图片保存到存储后端（本地磁盘或云存储）并返回可访问的 URL】 */
    private final FileStorageService storage;

    /**
     * 【构造注入，由 Spring 自动装配 UserService 和 FileStorageService】
     *
     * @param users   【用户服务，处理用户相关的业务逻辑】
     * @param storage 【文件存储服务，处理头像等文件的存储】
     */
    UserController(UserService users, FileStorageService storage) {
        super(users);
        this.storage = storage;
    }

    /**
     * 【获取当前登录用户的个人资料。
     *  从请求中解析 JWT 令牌对应的用户身份，调用 UserService.view() 转换为视图 DTO 返回。】
     *
     * @param request 【HTTP 请求，包含已认证用户的 JWT 信息】
     * @return 【用户资料视图对象，包含 id、username、nickname、email、avatarUrl、role 等字段】
     *
     * <p>GET /api/users/me — return the current user's profile.</p>
     */
    @GetMapping("/me")
    Object me(HttpServletRequest request) {
        return users.view(current(request));
    }

    /**
     * 【更新当前登录用户的个人资料（昵称、邮箱等）。
     *  仅更新请求体中非 null 的字段，实现部分更新语义。】
     *
     * @param request 【HTTP 请求，包含已认证用户的 JWT 信息】
     * @param body    【更新请求体，包含 nickname、email、avatarUrl 等可选字段】
     * @return 【更新后的用户资料视图对象】
     *
     * <p>PUT /api/users/me — update the current user's profile.</p>
     */
    @PutMapping("/me")
    Object update(HttpServletRequest request, @RequestBody ProfileRequest body) {
        return users.view(users.updateProfile(current(request), body));
    }

    /**
     * 【上传并设置当前用户的头像。
     *  1. 将上传的图片文件通过 FileStorageService 存储，获取访问 URL；
     *  2. 调用 UserService.setAvatar() 将 URL 关联到用户记录；
     *  3. 返回更新后的用户资料。】
     *
     * @param request 【HTTP 请求，包含已认证用户的 JWT 信息】
     * @param file    【上传的图片文件，通过 multipart/form-data 提交】
     * @return 【更新后的用户资料视图对象，包含新的 avatarUrl】
     *
     * <p>POST /api/users/avatar — upload a new avatar image.</p>
     */
    @PostMapping("/avatar")
    Object uploadAvatar(HttpServletRequest request, @RequestParam("file") MultipartFile file) {
        var url = storage.storeImage(file);
        var user = current(request);
        var updated = users.setAvatar(user, url);
        return users.view(updated);
    }
}

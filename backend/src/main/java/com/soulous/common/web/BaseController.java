package com.soulous.common.web;

import com.soulous.auth.UserAccount;
import com.soulous.auth.UserRole;
import com.soulous.auth.UserService;
import com.soulous.common.exception.ForbiddenException;
import com.soulous.common.exception.UnauthorizedException;
import jakarta.servlet.http.HttpServletRequest;
import org.springframework.security.core.context.SecurityContextHolder;

/**
 * 【控制器基类 —— 提供所有控制器共用的身份认证与授权辅助方法。
 * 子控制器继承此类后可直接调用 {@link #current} 获取当前已认证用户，
 * 或调用 {@link #admin} 获取并校验管理员身份，避免在每个接口中重复编写鉴权逻辑。】
 */
public abstract class BaseController {
    /** 【用户服务实例，供子类使用以执行用户相关操作】 */
    protected final UserService users;

    /**
     * 【构造函数，注入 UserService】
     *
     * @param users 【用户服务实例】
     */
    protected BaseController(UserService users) {
        this.users = users;
    }

    /**
     * 【获取当前已认证的用户信息。
     * 从 Spring Security 上下文中提取认证主体，若未认证或主体类型不匹配则抛出 401 异常。】
     *
     * @param request 【HTTP 请求对象（预留参数，便于未来扩展）】
     * @return 【当前已认证的用户账户信息】
     * @throws UnauthorizedException 【未认证时抛出】
     */
    protected UserAccount current(HttpServletRequest request) {
        var auth = SecurityContextHolder.getContext().getAuthentication();
        if (auth == null || !(auth.getPrincipal() instanceof UserAccount user)) {
            throw new UnauthorizedException("Not authenticated");
        }
        return user;
    }

    /**
     * 【获取当前已认证的管理员用户信息。
     * 先调用 {@link #current} 获取用户，再校验其角色是否为 ADMIN，
     * 若非管理员则抛出 403 异常。】
     *
     * @param request 【HTTP 请求对象】
     * @return 【当前已认证的管理员用户账户信息】
     * @throws UnauthorizedException 【未认证时抛出】
     * @throws ForbiddenException    【非管理员角色时抛出】
     */
    protected UserAccount admin(HttpServletRequest request) {
        var user = current(request);
        if (user.role != UserRole.ADMIN) {
            throw new ForbiddenException("Admin role required");
        }
        return user;
    }
}

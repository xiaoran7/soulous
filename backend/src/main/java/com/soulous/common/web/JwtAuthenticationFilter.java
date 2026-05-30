package com.soulous.common.web;

import com.soulous.auth.AuthController;
import com.soulous.auth.UserService;
import jakarta.servlet.FilterChain;
import jakarta.servlet.ServletException;
import jakarta.servlet.http.HttpServletRequest;
import jakarta.servlet.http.HttpServletResponse;
import org.springframework.security.authentication.UsernamePasswordAuthenticationToken;
import org.springframework.security.core.authority.SimpleGrantedAuthority;
import org.springframework.security.core.context.SecurityContextHolder;
import org.springframework.stereotype.Component;
import org.springframework.web.filter.OncePerRequestFilter;

import java.io.IOException;
import java.util.List;

/**
 * 【JWT 认证过滤器 —— 继承 OncePerRequestFilter 确保每次请求只执行一次。
 * 负责从 HTTP 请求中提取 JWT 令牌（优先从 Authorization 头读取 Bearer Token，
 * 其次从 Cookie 中读取），验证令牌有效性后将用户信息设置到 Spring Security 上下文中。
 * 如果令牌无效或已过期，则清除安全上下文，后续的授权检查将按未认证处理。】
 */
@Component
public class JwtAuthenticationFilter extends OncePerRequestFilter {
    /** 【用户服务，用于根据令牌查询/验证用户身份】 */
    private final UserService users;

    /**
     * 【构造函数，通过依赖注入获取 UserService 实例】
     *
     * @param users 【用户服务实例】
     */
    JwtAuthenticationFilter(UserService users) {
        this.users = users;
    }

    /**
     * 【核心过滤逻辑 —— 每次 HTTP 请求到达时执行。
     * 1. 从请求中提取 JWT 令牌
     * 2. 若令牌存在且非空，调用 UserService 验证并获取用户信息
     * 3. 构建 Spring Security 认证对象（包含用户角色权限）并设置到安全上下文
     * 4. 若验证失败（抛出 RuntimeException），清除安全上下文
     * 5. 无论成功与否，都将请求传递给过滤链的下一个过滤器】
     *
     * @param request  【HTTP 请求对象】
     * @param response 【HTTP 响应对象】
     * @param chain    【过滤链，用于传递请求给下一个过滤器】
     * @throws ServletException 【Servlet 异常】
     * @throws IOException      【IO 异常】
     */
    @Override
    protected void doFilterInternal(HttpServletRequest request, HttpServletResponse response, FilterChain chain)
            throws ServletException, IOException {
        var token = extractToken(request);
        if (token != null && !token.isBlank()) {
            try {
                var user = users.byToken(token);
                var authority = new SimpleGrantedAuthority("ROLE_" + user.role.name());
                var auth = new UsernamePasswordAuthenticationToken(user, null, List.of(authority));
                SecurityContextHolder.getContext().setAuthentication(auth);
            } catch (RuntimeException ignored) {
                SecurityContextHolder.clearContext();
            }
        }
        chain.doFilter(request, response);
    }

    /**
     * 【从 HTTP 请求中提取 JWT 令牌。
     * 提取策略：优先从 Authorization 请求头中解析 Bearer Token；
     * 若不存在，则遍历请求的 Cookie 查找名为 {@link AuthController#COOKIE_NAME} 的令牌。】
     *
     * @param request 【HTTP 请求对象】
     * @return 【提取到的 JWT 令牌字符串，若未找到则返回 null】
     */
    private String extractToken(HttpServletRequest request) {
        var header = request.getHeader("Authorization");
        if (header != null && header.startsWith("Bearer ")) {
            return header.substring(7);
        }
        var cookies = request.getCookies();
        if (cookies != null) {
            for (var cookie : cookies) {
                if (AuthController.COOKIE_NAME.equals(cookie.getName())) {
                    return cookie.getValue();
                }
            }
        }
        return null;
    }
}

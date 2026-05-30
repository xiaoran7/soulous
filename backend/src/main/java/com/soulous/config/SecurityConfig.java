package com.soulous.config;

import com.soulous.common.web.JwtAuthenticationFilter;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;
import org.springframework.http.HttpStatus;
import org.springframework.http.MediaType;
import org.springframework.security.config.annotation.web.builders.HttpSecurity;
import org.springframework.security.config.annotation.web.configuration.EnableWebSecurity;
import org.springframework.security.config.http.SessionCreationPolicy;
import org.springframework.security.web.SecurityFilterChain;
import org.springframework.security.web.authentication.UsernamePasswordAuthenticationFilter;
import org.springframework.web.cors.CorsConfiguration;
import org.springframework.web.cors.UrlBasedCorsConfigurationSource;

import java.util.ArrayList;
import java.util.List;

/**
 * 【Spring Security 安全配置类 —— 定义整个应用的安全过滤链。
 * 核心配置包括：
 * <ul>
 *   <li>CORS 跨域策略：根据配置文件中的允许源列表 + 开发环境额外源进行配置</li>
 *   <li>CSRF 防护：因采用无状态 JWT 认证，故禁用 CSRF</li>
 *   <li>会话管理：无状态模式（STATELESS），不创建 HttpSession</li>
 *   <li>URL 级别的访问控制：公开接口、管理员接口、认证接口的分级授权</li>
 *   <li>JWT 过滤器：在 UsernamePasswordAuthenticationFilter 之前插入自定义 JWT 认证过滤器</li>
 *   <li>异常处理：未认证和禁止访问时返回 JSON 格式的错误响应</li>
 * </ul>】
 */
@Configuration
@EnableWebSecurity
public class SecurityConfig {
    /**
     * 【构建安全过滤链 Bean —— 应用的核心安全规则定义。
     *
     * <p>配置要点：</p>
     * <ul>
     *   <li>CORS：从配置文件读取允许的源（逗号分隔），开发环境额外允许 localhost 的 Vite 开发服务器端口</li>
     *   <li>公开端点：/api/auth/**（认证相关）、/error（错误页面）、健康检查端点</li>
     *   <li>管理员端点：/api/admin/**、Prometheus 监控端点需要 ROLE_ADMIN 权限</li>
     *   <li>认证端点：/uploads/** 和其他所有请求需要已认证</li>
     *   <li>H2 控制台：开发环境可选开启，需要禁用 frameOptions 以支持 iframe 嵌入</li>
     * </ul>
     *
     * @param http              【HttpSecurity 构建器】
     * @param jwtFilter         【JWT 认证过滤器】
     * @param origin            【配置文件中的 CORS 允许源，逗号分隔】
     * @param h2ConsoleEnabled  【是否启用 H2 数据库控制台（开发用）】
     * @param devOriginsEnabled 【是否启用开发环境额外 CORS 源】
     * @return 【构建好的安全过滤链】
     * @throws Exception 【配置过程中的异常】
     */
    @Bean
    SecurityFilterChain securityFilterChain(HttpSecurity http,
                                             JwtAuthenticationFilter jwtFilter,
                                             @Value("${soulous.cors-origin}") String origin,
                                             @Value("${soulous.security.h2-console-enabled:true}") boolean h2ConsoleEnabled,
                                             @Value("${soulous.security.dev-origins-enabled:true}") boolean devOriginsEnabled) throws Exception {
        // 【配置 CORS 跨域策略】
        var cors = new CorsConfiguration();
        var origins = new ArrayList<String>();
        for (var o : origin.split(",")) {
            var trimmed = o.trim();
            if (!trimmed.isEmpty()) origins.add(trimmed);
        }
        // 【开发环境额外允许 Vite 开发服务器的常用端口】
        if (devOriginsEnabled) {
            origins.addAll(List.of("http://localhost:5174", "http://127.0.0.1:5173", "http://127.0.0.1:5174"));
        }
        cors.setAllowedOrigins(origins);
        cors.setAllowedHeaders(List.of("*"));
        cors.setAllowedMethods(List.of("*"));
        cors.setAllowCredentials(true);
        var source = new UrlBasedCorsConfigurationSource();
        source.registerCorsConfiguration("/**", cors);

        http
                // 【禁用 CSRF —— 因采用无状态 JWT 认证，无需 CSRF 保护】
                .csrf(c -> c.disable())
                // 【启用 CORS 并应用自定义配置源】
                .cors(c -> c.configurationSource(source))
                // 【设置会话策略为无状态，不创建 HttpSession】
                .sessionManagement(s -> s.sessionCreationPolicy(SessionCreationPolicy.STATELESS))
                .headers(h -> {
                    // 【若启用 H2 控制台，需禁用 frameOptions 以允许 iframe 嵌入】
                    if (h2ConsoleEnabled) h.frameOptions(f -> f.disable());
                })
                .authorizeHttpRequests(auth -> {
                    // 【公开端点：认证接口和错误页面无需认证】
                    auth.requestMatchers("/api/auth/**", "/error").permitAll();
                    // 【H2 控制台端点（仅开发环境）】
                    if (h2ConsoleEnabled) auth.requestMatchers("/h2-console/**").permitAll();
                    auth.requestMatchers("/actuator/health", "/actuator/info").permitAll()
                        // 【监控端点仅管理员可访问】
                        .requestMatchers("/actuator/prometheus", "/actuator/metrics/**").hasAuthority("ROLE_ADMIN")
                        // 【允许所有 OPTIONS 预检请求】
                        .requestMatchers(org.springframework.http.HttpMethod.OPTIONS, "/**").permitAll()
                        // 【管理员接口需要 ADMIN 角色】
                        .requestMatchers("/api/admin/**").hasAuthority("ROLE_ADMIN")
                        // 【上传文件目录需要已认证】
                        .requestMatchers("/uploads/**").authenticated()
                        // 【其他所有请求均需已认证】
                        .anyRequest().authenticated();
                })
                .exceptionHandling(e -> e
                        // 【未认证异常处理：返回 401 JSON 响应】
                        .authenticationEntryPoint((req, res, ex) -> {
                            res.setStatus(HttpStatus.UNAUTHORIZED.value());
                            res.setContentType(MediaType.APPLICATION_JSON_VALUE);
                            res.getWriter().write("{\"error\":\"Unauthorized\"}");
                        })
                        // 【权限不足异常处理：返回 403 JSON 响应】
                        .accessDeniedHandler((req, res, ex) -> {
                            res.setStatus(HttpStatus.FORBIDDEN.value());
                            res.setContentType(MediaType.APPLICATION_JSON_VALUE);
                            res.getWriter().write("{\"error\":\"Forbidden\"}");
                        }))
                // 【在 UsernamePasswordAuthenticationFilter 之前插入 JWT 认证过滤器】
                .addFilterBefore(jwtFilter, UsernamePasswordAuthenticationFilter.class);
        return http.build();
    }
}

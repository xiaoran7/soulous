package com.soulous.audit;

import com.soulous.auth.UserAccount;
import jakarta.servlet.http.HttpServletRequest;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.context.annotation.Lazy;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Propagation;
import org.springframework.transaction.annotation.Transactional;

/**
 * 【审计记录服务层，采用"发射后不管"（fire-and-forget）模式。
 * 所有公开的 record* 方法必须吞掉异常——丢失审计记录绝不能中断触发它的业务操作
 * （与 NotificationService.push 的设计理念一致）。
 *
 * 对于即将回滚的事务中发出的审计事件（典型场景：RefreshTokenService.rotate 的重放分支），
 * 应使用 recordInNewTransaction 方法，使审计记录在独立的新事务中持久化，从而在回滚后仍然保留。
 * 该方法通过 Spring 代理跳板开启 REQUIRES_NEW 事务传播——self 字段的存在原因与
 * RefreshTokenService.panicRevokeAllForUser 中的代理字段相同。】
 *
 * <p>Fire-and-forget audit recorder. Every public record* method MUST swallow
 * exceptions — losing an audit row should never break the business operation
 * that triggered it (compare {@code NotificationService.push}).</p>
 *
 * <p>For events emitted from inside a transaction that is about to be rolled back
 * (the canonical case: {@link com.soulous.auth.RefreshTokenService#rotate}
 * replay branch), call {@link #recordInNewTransaction} instead so the audit
 * row survives the rollback. That method opens a REQUIRES_NEW tx via a Spring
 * proxy hop — the {@link #self} field exists for the same reason
 * {@code RefreshTokenService.panicRevokeAllForUser} has one.</p>
 */
@Service
public class AuditService {
    private static final org.slf4j.Logger log = org.slf4j.LoggerFactory.getLogger(AuditService.class);

    private final AuditLogRepository repo;

    /** 【是否启用审计功能，通过配置项 soulous.audit.enabled 控制，默认为 true】 */
    private final boolean enabled;

    /**
     * 【自身代理引用，用于 REQUIRES_NEW 事务传播的代理跳板。
     * @Lazy 注解用于延迟注入，避免循环依赖初始化警告。】
     *
     * <p>See class javadoc — proxy hop for REQUIRES_NEW. @Lazy to dodge the circular-init warning.</p>
     */
    @Autowired @Lazy
    private AuditService self;

    AuditService(AuditLogRepository repo,
                 @Value("${soulous.audit.enabled:true}") boolean enabled) {
        this.repo = repo;
        this.enabled = enabled;
    }

    /**
     * 【在调用者的事务中记录审计日志（或开启新事务）。
     * 失败时静默处理，不影响业务操作。】
     *
     * <p>Records in the caller's transaction (or starts one). Fails silently.</p>
     *
     * @param action              【操作类型，如 LOGIN_SUCCESS、ADMIN_CREATE_USER 等】
     * @param actor               【操作者用户账号，可为 null】
     * @param actorUsernameOverride【操作者用户名覆盖值，当 actor 为 null 时使用】
     * @param targetType          【操作目标类型，如 USER、SUBMISSION 等】
     * @param targetId            【操作目标ID】
     * @param request             【HTTP 请求对象，用于获取 IP 和 User-Agent】
     * @param success             【操作是否成功】
     * @param details             【操作详情描述】
     */
    @Transactional
    public void record(String action, UserAccount actor, String actorUsernameOverride,
                       String targetType, Long targetId,
                       HttpServletRequest request, boolean success, String details) {
        if (!enabled) return;
        try {
            persist(action, actor, actorUsernameOverride, targetType, targetId, request, success, details);
        } catch (RuntimeException ex) {
            log.warn("audit record failed (action={} actor={})", action,
                    actor == null ? actorUsernameOverride : actor.username, ex);
        }
    }

    /**
     * 【便捷重载方法，适用于"操作者已知且无需用户名覆盖"的常见场景。】
     *
     * <p>Convenience overload for the common case of "actor known, no override needed".</p>
     */
    public void record(String action, UserAccount actor,
                       String targetType, Long targetId,
                       HttpServletRequest request, boolean success, String details) {
        record(action, actor, null, targetType, targetId, request, success, details);
    }

    /**
     * 【在全新的事务中记录审计日志，确保即使外层事务回滚，审计记录也能保留。
     * 通过 self 代理跳板调用 doInsertInNewTransaction，避免直接调用导致
     * 继承外层即将失败的事务。】
     *
     * <p>Runs in a FRESH transaction so the row survives if the surrounding tx rolls
     * back. The {@code self} proxy hop is required — calling {@link #doInsertInNewTransaction}
     * directly from inside the class would skip the proxy and inherit the doomed tx.</p>
     */
    public void recordInNewTransaction(String action, UserAccount actor, String actorUsernameOverride,
                                       String targetType, Long targetId,
                                       HttpServletRequest request, boolean success, String details) {
        if (!enabled) return;
        try {
            self.doInsertInNewTransaction(action, actor, actorUsernameOverride, targetType, targetId,
                    request, success, details);
        } catch (RuntimeException ex) {
            log.warn("audit recordInNewTransaction failed (action={} actor={})", action,
                    actor == null ? actorUsernameOverride : actor.username, ex);
        }
    }

    /**
     * 【实际执行审计记录持久化的方法，使用 REQUIRES_NEW 事务传播，
     * 确保在独立事务中执行，不受外层事务回滚影响。】
     *
     * @param action              【操作类型】
     * @param actor               【操作者用户账号】
     * @param actorUsernameOverride【操作者用户名覆盖值】
     * @param targetType          【操作目标类型】
     * @param targetId            【操作目标ID】
     * @param request             【HTTP 请求对象】
     * @param success             【操作是否成功】
     * @param details             【操作详情描述】
     */
    @Transactional(propagation = Propagation.REQUIRES_NEW)
    public void doInsertInNewTransaction(String action, UserAccount actor, String actorUsernameOverride,
                                         String targetType, Long targetId,
                                         HttpServletRequest request, boolean success, String details) {
        persist(action, actor, actorUsernameOverride, targetType, targetId, request, success, details);
    }

    /**
     * 【核心持久化方法，构建 AuditLog 实体并保存到数据库。
     * 处理操作者信息的快照（用户名、角色）、请求信息提取（IP、User-Agent）、
     * 以及字段长度截断等逻辑。】
     */
    private void persist(String action, UserAccount actor, String actorUsernameOverride,
                         String targetType, Long targetId,
                         HttpServletRequest request, boolean success, String details) {
        var row = new AuditLog();
        row.action = action;
        if (actor != null) {
            row.actorUserId = actor.id;
            row.actorUsername = actor.username;
            row.actorRole = actor.role == null ? null : actor.role.name();
        } else if (actorUsernameOverride != null) {
            row.actorUsername = truncate(actorUsernameOverride, 64);
        }
        row.targetType = truncate(targetType, 32);
        row.targetId = targetId;
        if (request != null) {
            row.ip = truncate(clientIp(request), 64);
            row.userAgent = truncate(request.getHeader("User-Agent"), 255);
        }
        row.success = success;
        row.details = details;
        row.createdAt = java.time.LocalDateTime.now();
        repo.save(row);
    }

    /**
     * 【获取客户端真实 IP 地址，支持 X-Forwarded-For 代理头解析。
     * 与 AuthController 和 RateLimitAspect 中的 IP 获取逻辑保持一致。】
     *
     * <p>X-Forwarded-For aware client IP, matching AuthController/RateLimitAspect.</p>
     *
     * @param request 【HTTP 请求对象】
     * @return 【客户端 IP 地址字符串】
     */
    public static String clientIp(HttpServletRequest request) {
        if (request == null) return null;
        var xff = request.getHeader("X-Forwarded-For");
        if (xff != null && !xff.isBlank()) {
            int comma = xff.indexOf(',');
            return (comma > 0 ? xff.substring(0, comma) : xff).trim();
        }
        return request.getRemoteAddr();
    }

    /**
     * 【将字符串截断到指定最大长度，防止数据库字段溢出。】
     *
     * @param s   【原始字符串】
     * @param max 【最大允许长度】
     * @return 【截断后的字符串，若输入为 null 则返回 null】
     */
    private static String truncate(String s, int max) {
        if (s == null) return null;
        return s.length() <= max ? s : s.substring(0, max);
    }
}

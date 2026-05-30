package com.soulous.audit;

import com.soulous.auth.UserService;
import com.soulous.common.web.BaseController;
import jakarta.servlet.http.HttpServletRequest;
import org.springframework.data.domain.PageRequest;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RequestParam;
import org.springframework.web.bind.annotation.RestController;

import java.time.LocalDateTime;
import java.time.format.DateTimeParseException;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;

/**
 * 【审计日志查询控制器，仅限管理员访问。
 * 提供分页、多条件筛选的审计日志查询接口。
 * 路径由 SecurityConfig 配置为 /api/admin/**，仅 ROLE_ADMIN 角色可访问。
 * 额外调用 admin() 方法进行权限校验，以返回项目统一的 403 友好提示。】
 *
 * <p>Admin-only audit log query endpoint. Path is gated by SecurityConfig
 * ({@code /api/admin/**} → ROLE_ADMIN); we additionally call {@code admin()}
 * here to mirror the rest of the AdminController family and to give a 403 with
 * the project's friendly message rather than the framework default.</p>
 */
@RestController
@RequestMapping("/api/admin/audit-log")
class AuditLogController extends BaseController {
    private final AuditLogRepository repo;

    AuditLogController(UserService users, AuditLogRepository repo) {
        super(users);
        this.repo = repo;
    }

    /**
     * 【审计日志分页查询接口。
     * 支持按操作类型（action）、操作者ID（actorUserId）、
     * 时间范围（from/to）进行筛选，返回分页结果。
     * 页码和每页大小均有安全边界限制。】
     *
     * @param request    【HTTP 请求对象，用于管理员权限校验】
     * @param action     【可选，按操作类型筛选】
     * @param actorUserId【可选，按操作者用户ID筛选】
     * @param from       【可选，起始时间（ISO-8601 格式）】
     * @param to         【可选，结束时间（ISO-8601 格式）】
     * @param page       【页码，默认为 0】
     * @param size       【每页大小，默认为 20，最大 200】
     * @return 【包含 items、page、size、totalElements、totalPages 的分页结果】
     */
    @GetMapping
    Map<String, Object> list(HttpServletRequest request,
                              @RequestParam(value = "action", required = false) String action,
                              @RequestParam(value = "actorUserId", required = false) Long actorUserId,
                              @RequestParam(value = "from", required = false) String from,
                              @RequestParam(value = "to", required = false) String to,
                              @RequestParam(value = "page", defaultValue = "0") int page,
                              @RequestParam(value = "size", defaultValue = "20") int size) {
        admin(request);
        var pageable = PageRequest.of(Math.max(0, page), Math.min(200, Math.max(1, size)));
        var p = repo.search(blankToNull(action), actorUserId, parse(from), parse(to), pageable);
        var body = new LinkedHashMap<String, Object>();
        body.put("items", view(p.getContent()));
        body.put("page", p.getNumber());
        body.put("size", p.getSize());
        body.put("totalElements", p.getTotalElements());
        body.put("totalPages", p.getTotalPages());
        return body;
    }

    /**
     * 【将空字符串或纯空白字符串转换为 null，用于可选查询参数的标准化处理。】
     *
     * @param s 【原始字符串】
     * @return 【若为 null 或空白则返回 null，否则返回 trim 后的字符串】
     */
    private static String blankToNull(String s) {
        return (s == null || s.isBlank()) ? null : s.trim();
    }

    /**
     * 【解析 ISO-8601 格式的时间字符串为 LocalDateTime。
     * 若输入为 null 或空白则返回 null；若格式无效则抛出 BadRequestException。】
     *
     * @param iso 【ISO-8601 格式的时间字符串】
     * @return 【解析后的 LocalDateTime 对象，或 null】
     */
    private static LocalDateTime parse(String iso) {
        if (iso == null || iso.isBlank()) return null;
        try {
            return LocalDateTime.parse(iso.trim());
        } catch (DateTimeParseException ex) {
            throw new com.soulous.common.exception.BadRequestException("无效的时间格式（需 ISO-8601 LocalDateTime）：" + iso);
        }
    }

    /**
     * 【将单条 AuditLog 实体转换为 Map 视图对象，用于 API 响应。
     * 按固定顺序输出所有字段，保证前端解析一致性。】
     *
     * @param a 【审计日志实体】
     * @return 【包含所有审计日志字段的有序 Map】
     */
    static Map<String, Object> view(AuditLog a) {
        var m = new LinkedHashMap<String, Object>();
        m.put("id", a.id);
        m.put("actorUserId", a.actorUserId);
        m.put("actorUsername", a.actorUsername);
        m.put("actorRole", a.actorRole);
        m.put("action", a.action);
        m.put("targetType", a.targetType);
        m.put("targetId", a.targetId);
        m.put("ip", a.ip);
        m.put("userAgent", a.userAgent);
        m.put("success", a.success);
        m.put("details", a.details);
        m.put("createdAt", a.createdAt);
        return m;
    }

    /**
     * 【批量将 AuditLog 列表转换为 Map 视图列表，用于分页响应。】
     *
     * @param rows 【审计日志实体列表】
     * @return 【转换后的 Map 视图列表】
     */
    static List<Map<String, Object>> view(List<AuditLog> rows) {
        return rows.stream().map(AuditLogController::view).toList();
    }
}

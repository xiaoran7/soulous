package com.soulous.rag;

import com.soulous.auth.UserService;
import com.soulous.common.web.BaseController;
import jakarta.servlet.http.HttpServletRequest;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RestController;

import java.util.Map;

/**
 * 【RAG 自助端点控制器】
 * 提供用户自助操作的 RAG 相关 HTTP 接口。目前仅支持当前用户自身的操作，
 * 管理员级/跨用户重建索引功能尚未暴露——需要时可添加使用 {@code admin(request)} 保护的端点。
 *
 * <p>English: Self-service RAG endpoints. Admin-only / cross-user reindex isn't exposed yet — add a
 * sibling endpoint guarded with {@code admin(request)} when needed.</p>
 */
@RestController
@RequestMapping("/api/rag")
class RagController extends BaseController {
    /** 【回填服务】用于重建用户语料库的索引。 */
    private final RagBackfillService backfill;

    /** 【检索服务】用于检查 RAG 功能是否可用。 */
    private final RetrievalService retrieval;

    /**
     * 【构造器】注入用户服务、回填服务和检索服务。
     *
     * @param users     用户服务（来自 BaseController）
     * @param backfill  RAG 回填服务
     * @param retrieval RAG 检索服务
     */
    RagController(UserService users, RagBackfillService backfill, RetrievalService retrieval) {
        super(users);
        this.backfill = backfill;
        this.retrieval = retrieval;
    }

    /**
     * 【RAG 状态探测接口】
     * 供 UI 或运维使用的轻量级状态检查端点——比执行一次重建索引来检查 RAG 是否启用更高效。
     * 需要用户登录认证。
     *
     * @param request HTTP 请求
     * @return 包含 "enabled" 字段的 Map
     *
     * <p>English: Status probe for the UI / ops — cheaper than running a reindex just to check whether
     * RAG is enabled at all.</p>
     */
    @GetMapping("/status")
    Map<String, Object> status(HttpServletRequest request) {
        current(request); // require auth
        return Map.of("enabled", retrieval.isEnabled());
    }

    /**
     * 【重建当前用户语料库索引】
     * 从头开始重新索引调用者自身的所有 RAG 可用数据。返回各类型的索引计数。
     * 可安全重复调用——upsert 操作是幂等的。
     * 当服务器级别 RAG 禁用时，返回全零计数和 {@code enabled:false}。
     *
     * @param request HTTP 请求
     * @return 包含 "enabled" 和 "counts" 字段的 Map
     *
     * <p>English: Re-index the caller's own corpus from scratch. Returns per-type counts. Safe to call
     * repeatedly — upserts are idempotent. Returns all-zero counts (and {@code enabled:false})
     * when RAG is disabled at the server level.</p>
     */
    @PostMapping("/reindex")
    Map<String, Object> reindexSelf(HttpServletRequest request) {
        var user = current(request);
        var counts = backfill.reindexUser(user);
        return Map.of(
                "enabled", retrieval.isEnabled(),
                "counts", counts
        );
    }
}

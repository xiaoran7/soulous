package com.soulous.account;

import com.soulous.audit.AuditLog;
import com.soulous.audit.AuditLogRepository;
import com.soulous.auth.UserService;
import com.soulous.common.exception.BadRequestException;
import com.soulous.common.web.BaseController;
import com.soulous.pet.Pet;
import com.soulous.pet.PetRepository;
import com.soulous.storage.FileStorageService;
import com.soulous.task.SubmissionRepository;
import jakarta.servlet.http.HttpServletRequest;
import org.springframework.data.domain.PageRequest;
import org.springframework.web.bind.annotation.DeleteMapping;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RequestParam;
import org.springframework.web.bind.annotation.RestController;

import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;

/**
 * 【账号自助控制器：设置页「安全与设备活动」「自定义资产与存储空间」两块的后端。
 *  全部限当前登录用户自己的数据。
 *  - 设备活动复用 audit_log（已记 IP/UA/动作/成功/时间）；
 *  - 存储资产是轻量聚合：不建文件登记表，从已知引用（账号头像 + 宠物头像 + 任务凭证图）现算，
 *    占用大小经 {@link FileStorageService#sizeOf} 读对象元信息得到。】
 */
@RestController
@RequestMapping("/api/account")
class AccountController extends BaseController {
    /** 通用安全审计日志仓库（已支持按 actorUserId 过滤） */
    private final AuditLogRepository audits;
    /** 宠物仓库：聚合宠物头像资产 + 按 URL 清除头像 */
    private final PetRepository pets;
    /** 任务提交仓库：聚合凭证截图资产 */
    private final SubmissionRepository submissions;
    /** 文件存储服务：URL→大小、URL→删除 */
    private final FileStorageService storage;

    AccountController(UserService users, AuditLogRepository audits, PetRepository pets,
                      SubmissionRepository submissions, FileStorageService storage) {
        super(users);
        this.audits = audits;
        this.pets = pets;
        this.submissions = submissions;
        this.storage = storage;
    }

    /** 【动作码 → 中文展示文案】 */
    private static final Map<String, String> ACTION_LABEL = Map.of(
            "LOGIN_SUCCESS", "登录成功",
            "LOGIN_FAILED", "登录失败",
            "LOGOUT", "登出",
            "LOGOUT_ALL", "退出所有设备",
            "PASSWORD_CHANGED", "修改密码",
            "REFRESH_TOKEN_REPLAYED", "令牌重放（安全告警）"
    );

    /**
     * 【当前用户的安全与设备活动】返回最近 30 条审计事件（登录/登出/改密等），含 IP、User-Agent、是否成功、时间。
     *
     * @param request HTTP 请求
     * @return 活动列表，按时间倒序
     */
    @GetMapping("/activity")
    List<Map<String, Object>> activity(HttpServletRequest request) {
        var user = current(request);
        var page = audits.search(null, user.id, null, null, PageRequest.of(0, 30));
        var out = new ArrayList<Map<String, Object>>();
        for (AuditLog a : page.getContent()) {
            var m = new LinkedHashMap<String, Object>();
            m.put("action", a.action);
            m.put("label", ACTION_LABEL.getOrDefault(a.action, a.action));
            m.put("ip", a.ip == null ? "" : a.ip);
            m.put("userAgent", a.userAgent == null ? "" : a.userAgent);
            m.put("success", a.success);
            m.put("createdAt", a.createdAt);
            out.add(m);
        }
        return out;
    }

    /**
     * 【当前用户的存储资产聚合视图】列出账号头像、宠物头像、任务凭证图，给出各自占用与总占用。
     * 凭证图受任务引用、不可在此删除（deletable=false）；头像类可删。
     *
     * @param request HTTP 请求
     * @return {items:[...], totalBytes:N}
     */
    @GetMapping("/assets")
    Map<String, Object> assets(HttpServletRequest request) {
        var user = current(request);
        var items = new ArrayList<Map<String, Object>>();

        if (user.avatarUrl != null && !user.avatarUrl.isBlank()) {
            items.add(asset("ACCOUNT_AVATAR", "账号头像", user.avatarUrl, true));
        }
        for (Pet p : pets.findByUserOrderByAcquiredAtAsc(user)) {
            if (p.avatarUrl != null && !p.avatarUrl.isBlank()) {
                items.add(asset("PET_AVATAR", "宠物头像 · " + (p.name == null ? "宠物" : p.name), p.avatarUrl, true));
            }
        }
        for (var s : submissions.findByUserOrderByCreatedAtDesc(user)) {
            addProof(items, s.screenshotUrl);
            for (var url : splitUrls(s.screenshotUrls)) addProof(items, url);
        }

        long total = items.stream().mapToLong(it -> ((Number) it.get("sizeBytes")).longValue()).sum();
        return Map.of("items", items, "totalBytes", total);
    }

    /**
     * 【删除一项存储资产（仅头像类）】清除引用该 URL 的头像字段并删除底层对象。
     * 任务凭证图受任务引用，拒绝在此删除。
     *
     * @param url     资源 URL（/uploads/<key>）
     * @param request HTTP 请求
     * @return {ok:true}
     * @throws BadRequestException URL 不属于当前用户的可删资产时
     */
    @DeleteMapping("/assets")
    Map<String, Object> deleteAsset(@RequestParam("url") String url, HttpServletRequest request) {
        var user = current(request);
        if (url == null || url.isBlank()) throw new BadRequestException("缺少 url");

        // 账号头像
        if (url.equals(user.avatarUrl)) {
            users.setAvatar(user, null);
            storage.deleteByUrl(url);
            return Map.of("ok", true);
        }
        // 宠物头像（owned）
        for (Pet p : pets.findByUserOrderByAcquiredAtAsc(user)) {
            if (url.equals(p.avatarUrl)) {
                p.avatarUrl = null;
                pets.save(p);
                storage.deleteByUrl(url);
                return Map.of("ok", true);
            }
        }
        throw new BadRequestException("该资产不可在此删除（任务凭证图受任务引用，或不属于你）");
    }

    /** 【构建单条资产视图】 */
    private Map<String, Object> asset(String kind, String label, String url, boolean deletable) {
        var m = new LinkedHashMap<String, Object>();
        m.put("kind", kind);
        m.put("label", label);
        m.put("url", url);
        m.put("sizeBytes", storage.sizeOf(url));
        m.put("deletable", deletable);
        return m;
    }

    /** 【追加一条任务凭证图资产（去重、跳过空与外链）】 */
    private void addProof(List<Map<String, Object>> items, String url) {
        if (url == null || url.isBlank()) return;
        if (FileStorageService.keyFromUrl(url) == null) return; // 仅本地上传计入
        for (var it : items) if (url.equals(it.get("url"))) return; // 去重
        items.add(asset("TASK_PROOF", "任务凭证图", url, false));
    }

    /** 【拆分 CSV/JSON 形式的多图字符串为 URL 列表】与 StorageGcTask 的解析口径一致。 */
    private static List<String> splitUrls(String csvOrJson) {
        if (csvOrJson == null || csvOrJson.isBlank()) return List.of();
        var out = new ArrayList<String>();
        for (var token : csvOrJson.split("[,\\[\\]\"\\s]+")) {
            if (!token.isBlank()) out.add(token);
        }
        return out;
    }
}

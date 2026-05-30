package com.soulous.storage;

import com.soulous.appeal.AppealRepository;
import com.soulous.auth.UserRepository;
import com.soulous.pet.PetRepository;
import com.soulous.task.SubmissionRepository;
import io.micrometer.core.instrument.MeterRegistry;
import io.micrometer.core.instrument.simple.SimpleMeterRegistry;
import org.slf4j.Logger;
import org.springframework.beans.factory.annotation.Autowired;
import org.slf4j.LoggerFactory;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.scheduling.annotation.Scheduled;
import org.springframework.stereotype.Component;

import java.io.IOException;
import java.time.Duration;
import java.time.Instant;
import java.util.HashSet;
import java.util.Set;

/**
 * 【存储垃圾回收定时任务：每晚扫描对象存储，删除超过 minAge 且未被
 * 任何 submission、appeal、用户头像或宠物头像引用的孤儿对象。
 *
 * 默认 dry-run=true 仅记录日志不实际删除，建议先以 dry-run 模式运行约 1 周，
 * 审查日志后再切换为实际删除模式。】
 *
 * <p>Nightly scan of the object store: any object older than {@code minAge} that is
 * not referenced by any submission, appeal, user avatar or pet avatar is an
 * orphan and gets deleted.</p>
 *
 * <p>Default {@code soulous.storage.gc.dry-run=true} — only logs what *would* be
 * deleted. Run for ~1 week in dry-run, eyeball the log, then flip the flag.</p>
 */
@Component
public class StorageGcTask {
    private static final Logger log = LoggerFactory.getLogger(StorageGcTask.class);

    /** 对象存储后端（本地或 S3） */
    private final ObjectStorage storage;
    /** 提交记录仓库，用于收集被引用的文件 key */
    private final SubmissionRepository submissions;
    /** 申诉记录仓库，用于收集被引用的文件 key */
    private final AppealRepository appeals;
    /** 用户仓库，用于收集头像引用 */
    private final UserRepository users;
    /** 宠物仓库，用于收集头像引用 */
    private final PetRepository pets;
    /** 对象最小存活时间，低于此年龄的对象不参与 GC */
    private final Duration minAge;
    /** 是否为试运行模式（仅日志，不实际删除） */
    private final boolean dryRun;
    /** 是否启用 GC 任务 */
    private final boolean enabled;
    /** Micrometer 指标注册器 */
    private final MeterRegistry meterRegistry;

    /**
     * 【主构造器：通过依赖注入获取所有必要组件和配置参数】
     *
     * @param storage      【对象存储实现】
     * @param submissions  【提交记录仓库】
     * @param appeals      【申诉记录仓库】
     * @param users        【用户仓库】
     * @param pets         【宠物仓库】
     * @param enabled      【是否启用 GC，通过 soulous.storage.gc.enabled 配置】
     * @param minAgeHours  【最小存活小时数，通过 soulous.storage.gc.min-age-hours 配置】
     * @param dryRun       【是否试运行，通过 soulous.storage.gc.dry-run 配置】
     * @param meterRegistry【指标监控注册器】
     */
    @Autowired
    public StorageGcTask(ObjectStorage storage,
                         SubmissionRepository submissions,
                         AppealRepository appeals,
                         UserRepository users,
                         PetRepository pets,
                         @Value("${soulous.storage.gc.enabled:true}") boolean enabled,
                         @Value("${soulous.storage.gc.min-age-hours:24}") int minAgeHours,
                         @Value("${soulous.storage.gc.dry-run:true}") boolean dryRun,
                         MeterRegistry meterRegistry) {
        this.storage = storage;
        this.submissions = submissions;
        this.appeals = appeals;
        this.users = users;
        this.pets = pets;
        this.minAge = Duration.ofHours(Math.max(1, minAgeHours));
        this.dryRun = dryRun;
        this.enabled = enabled;
        this.meterRegistry = meterRegistry;
    }

    /** 【测试用简化构造器，默认使用独立的 SimpleMeterRegistry。】
     *  Legacy ctor used by tests — defaults to a stand-alone {@link SimpleMeterRegistry}. */
    public StorageGcTask(ObjectStorage storage,
                         SubmissionRepository submissions,
                         AppealRepository appeals,
                         UserRepository users,
                         PetRepository pets,
                         boolean enabled,
                         int minAgeHours,
                         boolean dryRun) {
        this(storage, submissions, appeals, users, pets, enabled, minAgeHours, dryRun, new SimpleMeterRegistry());
    }

    /** 【定时执行入口：每天凌晨 3:00 运行，可通过 soulous.storage.gc.cron 自定义 cron 表达式。】
     *  Run every day at 03:00 server time. */
    @Scheduled(cron = "${soulous.storage.gc.cron:0 0 3 * * *}")
    public void scheduledRun() {
        if (!enabled) return;
        try {
            runOnce();
        } catch (Exception ex) {
            log.warn("Storage GC run failed", ex);
        }
    }

    /** 【暴露给测试和管理后台的手动执行入口。返回（将要）删除的 key 数量。】
     *  Exposed for tests + admin trigger. Returns count of (would-be) deleted keys. */
    public int runOnce() throws IOException {
        var threshold = Instant.now().minus(minAge);
        var candidates = storage.listOlderThan(threshold);
        if (candidates.isEmpty()) {
            log.info("Storage GC: 0 candidates older than {}", threshold);
            return 0;
        }
        Set<String> referenced = collectReferencedKeys();

        int gone = 0;
        long bytesFreed = 0;
        for (var info : candidates) {
            if (referenced.contains(info.key())) continue;
            if (dryRun) {
                log.info("Storage GC [DRY-RUN] would delete key={} size={}B mtime={}",
                        info.key(), info.size(), info.lastModified());
            } else {
                try {
                    storage.delete(info.key());
                    log.info("Storage GC deleted key={} size={}B mtime={}",
                            info.key(), info.size(), info.lastModified());
                } catch (IOException ex) {
                    log.warn("Storage GC failed to delete {}", info.key(), ex);
                    continue;
                }
            }
            gone++;
            bytesFreed += info.size();
        }
        log.info("Storage GC: {} candidates, {} {} ({} bytes), {} referenced kept",
                candidates.size(), gone, dryRun ? "would-delete" : "deleted",
                bytesFreed, candidates.size() - gone);
        meterRegistry.counter("soulous.storage.gc.deleted.total").increment(gone);
        return gone;
    }

    /** 【遍历所有存储 /uploads/ 引用的实体，提取 key 部分，构建已引用 key 集合。】
     *  Walk every entity that stores a /uploads/ reference and collect just the key portion. */
    private Set<String> collectReferencedKeys() {
        var keys = new HashSet<String>();
        submissions.findAll().forEach(s -> {
            addKey(keys, s.screenshotUrl);
            addKeysFromList(keys, s.screenshotUrls);
        });
        appeals.findAll().forEach(a -> addKeysFromList(keys, a.screenshotUrls));
        users.findAll().forEach(u -> addKey(keys, u.avatarUrl));
        pets.findAll().forEach(p -> addKey(keys, p.avatarUrl));
        return keys;
    }

    /**
     * 【从 URL 中提取文件 key 并加入集合。URL 格式为 /uploads/<key>，取最后一个 / 后的部分。】
     *
     * @param keys 【已引用 key 集合】
     * @param url  【文件 URL】
     */
    private static void addKey(Set<String> keys, String url) {
        if (url == null || url.isBlank()) return;
        var idx = url.lastIndexOf('/');
        if (idx < 0 || idx == url.length() - 1) return;
        keys.add(url.substring(idx + 1));
    }

    /**
     * 【从 CSV 或 JSON 数组格式的字符串中解析多个 URL 并提取 key。
     * 兼容两种格式：CSV（"/uploads/a.jpg,/uploads/b.jpg"）和 JSON 数组。】
     *
     * @param keys      【已引用 key 集合】
     * @param csvOrJson 【CSV 或 JSON 格式的 URL 列表】
     */
    private static void addKeysFromList(Set<String> keys, String csvOrJson) {
        if (csvOrJson == null || csvOrJson.isBlank()) return;
        // accept both CSV ("/uploads/a.jpg,/uploads/b.jpg") and JSON array shapes
        // 兼容 CSV 和 JSON 数组两种格式
        for (var token : csvOrJson.split("[,\\[\\]\"\\s]+")) {
            if (!token.isBlank()) addKey(keys, token);
        }
    }
}

package com.soulous.common.health;

import com.soulous.storage.ObjectStorage;
import org.springframework.boot.actuate.health.Health;
import org.springframework.boot.actuate.health.HealthIndicator;
import org.springframework.boot.autoconfigure.condition.ConditionalOnProperty;
import org.springframework.stereotype.Component;

/**
 * 【存储健康检查指示器：通过在对象存储中执行一次完整的 写入→读取→删除 探测流程，
 * 验证配置的对象存储后端是否端到端可达。
 *
 * 默认关闭，需设置 {@code soulous.health.storage-check.enabled=true} 启用。
 *
 * 设计要点：
 * - 探测键使用固定值 {@code .__health}（而非随机值），即使探测过程中删除步骤泄漏，
 *   存储 GC 也不会累积孤儿对象，因为下次探测会覆盖同一键。】
 *
 * <p>Reads/writes/deletes a tiny probe object under a fixed key to verify the configured
 * object store backend is reachable end-to-end. Off by default; flip
 * {@code soulous.health.storage-check.enabled=true} to opt in.</p>
 *
 * <p>The probe key is fixed ({@code .__health}) rather than random so the storage GC
 * won't accumulate orphans even if a probe leaks past the delete step.</p>
 */
@Component
@ConditionalOnProperty(name = "soulous.health.storage-check.enabled", havingValue = "true", matchIfMissing = false)
public class StorageHealthIndicator implements HealthIndicator {
    /** 【探测对象的固定键名】 */
    private static final String PROBE_KEY = ".__health";
    /** 【探测对象的有效载荷（1 字节占位数据）】 */
    private static final byte[] PROBE_PAYLOAD = new byte[]{1};

    /** 【对象存储服务实例】 */
    private final ObjectStorage storage;

    /**
     * 【构造函数：注入对象存储服务实例】
     *
     * @param storage 【ObjectStorage 实例】
     */
    public StorageHealthIndicator(ObjectStorage storage) {
        this.storage = storage;
    }

    /**
     * 【执行存储健康检查：
     * 1. 将探测对象写入存储
     * 2. 尝试读取该对象，验证写入成功
     * 3. 删除探测对象，清理资源
     * 4. 全流程成功返回 UP，任一步骤异常返回 DOWN】
     *
     * @return 【Health 状态对象，UP 时包含后端名称信息】
     */
    @Override
    public Health health() {
        try {
            storage.store(PROBE_KEY, PROBE_PAYLOAD, "application/octet-stream");
            var loaded = storage.load(PROBE_KEY);
            if (loaded.isEmpty()) {
                return Health.down().withDetail("reason", "probe key missing after store").build();
            }
            storage.delete(PROBE_KEY);
            return Health.up().withDetail("backend", storage.backendName()).build();
        } catch (Exception ex) {
            return Health.down().withException(ex).build();
        }
    }
}

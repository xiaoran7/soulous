package com.soulous.auth;

import com.soulous.common.exception.BadRequestException;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.beans.factory.ObjectProvider;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.mail.SimpleMailMessage;
import org.springframework.mail.javamail.JavaMailSender;
import org.springframework.stereotype.Service;

import java.security.SecureRandom;
import java.time.Instant;
import java.util.Comparator;
import java.util.Map;
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.ConcurrentMap;
import java.util.concurrent.atomic.AtomicInteger;
import java.util.regex.Pattern;

/**
 * 【邮箱验证码服务：注册流程用，负责 6 位数字验证码的发放、发送、校验与生命周期管理。
 *  采用内存存储（ConcurrentHashMap，按邮箱归一化后作 key），适用于单实例部署；
 *  多实例共享时可替换为 Redis。设计参考 {@link CaptchaService}，额外增加按邮箱的「重发冷却」。
 *
 *  <p>发送策略（开发期回退）：仅当配置了 {@code spring.mail.host} 且 JavaMailSender 可用时才真正发信；
 *  否则把验证码打到后端日志（WARN 级），便于本地/未配 SMTP 时联调。配好 SMTP 后无需改码即可真发。】
 */
@Service
public class EmailCodeService {
    private static final Logger log = LoggerFactory.getLogger(EmailCodeService.class);

    /** 【邮箱格式正则，与前端/UserService 保持一致】 */
    private static final Pattern EMAIL_RE = Pattern.compile("^[^\\s@]+@[^\\s@]+\\.[^\\s@]+$");

    /** 【存储最大条目数，超出按过期时间淘汰，防止内存无限增长】 */
    private static final int MAX_ENTRIES = 5000;

    /** 【同一验证码允许的最大校验尝试次数，超过立即作废】 */
    private static final int MAX_ATTEMPTS = 5;

    /** 【是否启用邮箱验证码（关闭后 verify/requestCode 直接放行，仅自动化测试用）】 */
    private final boolean enabled;
    /** 【验证码有效期（秒），默认 600（10 分钟）】 */
    private final long ttlSeconds;
    /** 【同一邮箱两次发码的最小间隔（秒），默认 60，防刷】 */
    private final long resendCooldownSeconds;
    /** 【SMTP 主机；为空表示未配置 → 走开发期日志回退】 */
    private final String mailHost;
    /** 【发件人地址】 */
    private final String fromAddress;
    /** 【邮件主题前缀】 */
    private final String subjectPrefix;
    /** 【JavaMailSender 提供者；未配置 SMTP 时该 Bean 不存在，按需取用】 */
    private final ObjectProvider<JavaMailSender> mailProvider;

    private final SecureRandom random = new SecureRandom();
    /** 【验证码存储，key = 归一化邮箱，value = 验证码 + 过期时间 + 上次发送时间 + 尝试次数】 */
    private final ConcurrentMap<String, Entry> store = new ConcurrentHashMap<>();

    EmailCodeService(@Value("${soulous.auth.email-code.enabled:true}") boolean enabled,
                     @Value("${soulous.auth.email-code.ttl-seconds:600}") long ttlSeconds,
                     @Value("${soulous.auth.email-code.resend-cooldown-seconds:60}") long resendCooldownSeconds,
                     @Value("${spring.mail.host:}") String mailHost,
                     @Value("${soulous.notification.email.from:no-reply@soulous.local}") String fromAddress,
                     @Value("${soulous.notification.email.subject-prefix:[Soulous] }") String subjectPrefix,
                     ObjectProvider<JavaMailSender> mailProvider) {
        this.enabled = enabled;
        this.ttlSeconds = ttlSeconds;
        this.resendCooldownSeconds = resendCooldownSeconds;
        this.mailHost = mailHost;
        this.fromAddress = fromAddress;
        this.subjectPrefix = subjectPrefix;
        this.mailProvider = mailProvider;
    }

    /** 【是否启用，供 Controller 判断】 */
    public boolean isEnabled() { return enabled; }

    /**
     * 【发放并发送验证码：归一化邮箱 → 冷却校验 → 生成 6 位码 → 落存储 → 发送（或日志回退）。】
     *
     * @param rawEmail 【目标邮箱】
     * @throws BadRequestException 【邮箱格式非法、发送过于频繁，或真实发信失败时抛出】
     */
    public void requestCode(String rawEmail) {
        if (!enabled) return;
        var email = normalize(rawEmail);
        purgeExpired();
        purgeIfOverCapacity();
        var now = Instant.now();
        var existing = store.get(email);
        if (existing != null && existing.lastSentAt.plusSeconds(resendCooldownSeconds).isAfter(now)) {
            throw new BadRequestException("验证码发送过于频繁，请稍后再试");
        }
        var code = String.format("%06d", random.nextInt(1_000_000));
        store.put(email, new Entry(code, now.plusSeconds(ttlSeconds), now));
        send(email, code);
    }

    /**
     * 【校验验证码：成功后一次性作废；失败累计尝试，超限作废。】
     *
     * @param rawEmail 【注册所用邮箱，需与发码邮箱一致】
     * @param code     【用户输入的验证码】
     * @throws BadRequestException 【邮箱非法、验证码无效/过期/错误/错误次数过多时抛出】
     */
    public void verify(String rawEmail, String code) {
        if (!enabled) return;
        if (code == null || code.isBlank()) throw new BadRequestException("请输入邮箱验证码");
        var email = normalize(rawEmail);
        var entry = store.get(email);
        if (entry == null) throw new BadRequestException("验证码无效，请重新获取");
        if (entry.expiresAt.isBefore(Instant.now())) {
            store.remove(email);
            throw new BadRequestException("验证码已过期，请重新获取");
        }
        if (!entry.code.equals(code.trim())) {
            int used = entry.attempts.incrementAndGet();
            if (used >= MAX_ATTEMPTS) {
                store.remove(email);
                throw new BadRequestException("验证码错误次数过多，请重新获取");
            }
            throw new BadRequestException("验证码不正确");
        }
        store.remove(email);
    }

    /**
     * 【发送验证码邮件：未配 SMTP 时回退为日志输出（开发期）；
     *  已配 SMTP 但发送失败时抛 BadRequest，让用户感知并重试。】
     */
    private void send(String email, String code) {
        var minutes = Math.max(1, ttlSeconds / 60);
        var text = "你的 Soulous 注册验证码是：" + code
                + "\n\n验证码 " + minutes + " 分钟内有效，请勿泄露给他人。\n\n— Soulous";
        if (mailHost == null || mailHost.isBlank()) {
            log.warn("[DEV][email-code] 未配置 SMTP，邮箱 {} 的注册验证码 = {}", email, code);
            return;
        }
        var sender = mailProvider.getIfAvailable();
        if (sender == null) {
            log.warn("[DEV][email-code] JavaMailSender 不可用，邮箱 {} 的注册验证码 = {}", email, code);
            return;
        }
        try {
            var msg = new SimpleMailMessage();
            msg.setFrom(fromAddress);
            msg.setTo(email);
            msg.setSubject(subjectPrefix + "注册验证码");
            msg.setText(text);
            sender.send(msg);
        } catch (RuntimeException ex) {
            log.warn("email code dispatch failed (to={}): {}", email, ex.getMessage());
            throw new BadRequestException("验证码邮件发送失败，请稍后重试");
        }
    }

    /** 【归一化邮箱：trim + 转小写，并做格式与长度校验】 */
    private String normalize(String email) {
        if (email == null) throw new BadRequestException("邮箱不能为空");
        var t = email.trim().toLowerCase();
        if (t.isEmpty() || t.length() > 254 || !EMAIL_RE.matcher(t).matches()) {
            throw new BadRequestException("邮箱格式不正确");
        }
        return t;
    }

    /** 【惰性清理过期条目】 */
    private void purgeExpired() {
        var now = Instant.now();
        store.entrySet().removeIf(e -> e.getValue().expiresAt.isBefore(now));
    }

    /** 【容量溢出保护：超额时丢弃最先过期的一批】 */
    private void purgeIfOverCapacity() {
        if (store.size() < MAX_ENTRIES) return;
        store.entrySet().stream()
                .sorted(Comparator.comparing(e -> e.getValue().expiresAt))
                .limit(Math.max(1, store.size() - MAX_ENTRIES + 1))
                .map(Map.Entry::getKey)
                .forEach(store::remove);
    }

    /**
     * 【验证码存储条目】
     *
     * @param code       【6 位数字验证码】
     * @param expiresAt  【过期时间点】
     * @param lastSentAt 【上次发送时间，用于重发冷却】
     * @param attempts   【已尝试校验次数】
     */
    private record Entry(String code, Instant expiresAt, Instant lastSentAt, AtomicInteger attempts) {
        Entry(String code, Instant expiresAt, Instant lastSentAt) {
            this(code, expiresAt, lastSentAt, new AtomicInteger(0));
        }
    }
}

package com.soulous.auth;

import com.soulous.common.exception.BadRequestException;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.stereotype.Service;

import java.nio.charset.StandardCharsets;
import java.security.SecureRandom;
import java.time.Instant;
import java.util.Base64;
import java.util.Comparator;
import java.util.Map;
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.ConcurrentMap;
import java.util.concurrent.atomic.AtomicInteger;
import java.util.concurrent.atomic.AtomicLong;

/**
 * 【验证码服务，负责验证码的生成、校验和生命周期管理。
 * 采用内存存储（ConcurrentHashMap），适用于单实例部署；如需多实例共享，
 * 可替换为 Redis 等外部存储。验证码以 SVG 图片形式返回，前端可直接嵌入 <img> 标签。
 * 通过配置项 soulous.captcha.enabled 控制是否启用验证码功能。】
 *
 * @see CaptchaResponse 【验证码响应 DTO，包含 id 和 SVG 图片】
 */
@Service
public class CaptchaService {

    /**
     * 【验证码字符集，去掉了易混淆的字符：
     * 0（零）与 O（字母O）、1（一）与 I（字母I）/ l（小写L），
     * 降低用户辨认难度，同时保留 32 个字符保证熵值充足】
     */
    private static final char[] ALPHABET = "23456789ABCDEFGHJKLMNPQRSTUVWXYZ".toCharArray(); // 去掉易混淆的 0/O/1/I

    /** 【验证码存储的最大条目数，超过后会触发清理策略，防止内存无限增长】 */
    private static final int MAX_ENTRIES = 1000;

    /** 【同一张验证码图片允许的最大验证尝试次数，超过后验证码立即作废，需重新获取】 */
    private static final int MAX_ATTEMPTS = 5;

    /** 【全局自增 ID 生成器，用于生成验证码唯一标识，结合随机数避免可预测性】 */
    private static final AtomicLong ID_GEN = new AtomicLong();

    /** 【是否启用验证码功能，通过配置项 soulous.captcha.enabled 控制】 */
    private final boolean enabled;

    /** 【验证码有效期（秒），通过配置项 soulous.captcha.ttl-seconds 控制，默认 120 秒】 */
    private final long ttlSeconds;

    /** 【安全随机数生成器，用于生成验证码字符和唯一 ID，比 Math.random() 更安全】 */
    private final SecureRandom random = new SecureRandom();

    /** 【验证码存储，key 为验证码 ID，value 为包含验证码值、过期时间和尝试次数的 Entry】 */
    private final ConcurrentMap<String, Entry> store = new ConcurrentHashMap<>();

    /**
     * 【构造函数，通过 Spring 依赖注入配置参数】
     *
     * @param enabled    【是否启用验证码，配置项 soulous.captcha.enabled，默认 true】
     * @param ttlSeconds 【验证码有效期秒数，配置项 soulous.captcha.ttl-seconds，默认 120】
     */
    CaptchaService(@Value("${soulous.captcha.enabled:true}") boolean enabled,
                   @Value("${soulous.captcha.ttl-seconds:120}") long ttlSeconds) {
        this.enabled = enabled;
        this.ttlSeconds = ttlSeconds;
    }

    /**
     * 【查询验证码功能是否启用，供 Controller 层判断是否需要展示验证码】
     *
     * @return 【true 表示启用，false 表示禁用】
     */
    public boolean isEnabled() { return enabled; }

    /**
     * 【生成并返回一个新的验证码。
     * 流程：1. 清理过期条目 2. 容量溢出保护 3. 生成 4 位随机字符 4. 生成唯一 ID
     * 5. 存入内存 6. 渲染为 SVG 图片并返回。
     * ID 格式为 "自增序号-base36(随机数)"，兼顾唯一性和不可预测性。】
     *
     * @return 【包含验证码 ID 和 SVG data URI 的响应对象】
     */
    public CaptchaResponse issue() {
        purgeExpired();
        purgeIfOverCapacity();
        // 【生成 4 位随机验证码字符】
        var code = new char[4];
        for (int i = 0; i < code.length; i++) code[i] = ALPHABET[random.nextInt(ALPHABET.length)];
        // 【生成唯一 ID：自增序号(36进制) + "-" + 随机数低32位(36进制)，兼顾可排序性和随机性】
        var id = Long.toString(ID_GEN.incrementAndGet(), 36) + "-" + Long.toString(random.nextLong() & 0xFFFFFFFFL, 36);
        store.put(id, new Entry(new String(code), Instant.now().plusSeconds(ttlSeconds)));
        return new CaptchaResponse(id, renderSvg(new String(code)));
    }

    /**
     * 【校验验证码。校验通过后 id 立即作废（一次性）；
     * 校验失败不立刻作废，允许同一张图最多 {@value #MAX_ATTEMPTS} 次重试，避免一次手误就要换图。
     * 但失败次数超出上限、id 不存在、或已过期都会抛 BadRequest。】
     *
     * @param id   【验证码 ID，由 issue() 方法返回】
     * @param code 【用户输入的验证码，不区分大小写，前后空格会被自动去除】
     * @throws BadRequestException 【验证码功能关闭时直接跳过；
     *                              id 或 code 为空时提示"请输入验证码"；
     *                              id 不存在时提示"验证码无效，请刷新"；
     *                              已过期时提示"验证码已过期，请刷新"；
     *                              超过最大尝试次数时提示"验证码错误次数过多，请刷新"；
     *                              验证码不正确时提示"验证码不正确"】
     */
    public void verify(String id, String code) {
        // 【验证码功能关闭时直接放行，不做任何校验】
        if (!enabled) return;
        if (id == null || id.isBlank() || code == null || code.isBlank()) {
            throw new BadRequestException("请输入验证码");
        }
        var entry = store.get(id);
        if (entry == null) {
            throw new BadRequestException("验证码无效，请刷新");
        }
        // 【检查是否已过期，过期则从存储中移除】
        if (entry.expiresAt.isBefore(Instant.now())) {
            store.remove(id);
            throw new BadRequestException("验证码已过期，请刷新");
        }
        // 【不区分大小写比较，去除用户输入首尾空格】
        if (!entry.code.equalsIgnoreCase(code.trim())) {
            int used = entry.attempts.incrementAndGet();
            // 【超过最大尝试次数，立即作废该验证码，防止暴力破解】
            if (used >= MAX_ATTEMPTS) {
                store.remove(id);
                throw new BadRequestException("验证码错误次数过多，请刷新");
            }
            throw new BadRequestException("验证码不正确");
        }
        // 【验证成功，立即移除验证码（一次性使用），防止重放攻击】
        store.remove(id);
    }

    /**
     * 【清理所有已过期的验证码条目，释放内存。
     * 在每次生成新验证码时调用，属于惰性清理策略。】
     */
    private void purgeExpired() {
        var now = Instant.now();
        store.entrySet().removeIf(e -> e.getValue().expiresAt.isBefore(now));
    }

    /**
     * 【容量溢出保护：当存储条目超过 MAX_ENTRIES 时，
     * 按过期时间升序排列，丢弃最先过期的一批条目，确保存储不会无限增长。
     * 此方法在 purgeExpired() 之后调用，因此此时仍超容量说明合法条目过多。】
     */
    private void purgeIfOverCapacity() {
        if (store.size() < MAX_ENTRIES) return;
        // 在已经清理过期项之后仍超容量，丢弃最先过期的一批
        store.entrySet().stream()
                .sorted(Comparator.comparing(e -> e.getValue().expiresAt))
                .limit(Math.max(1, store.size() - MAX_ENTRIES + 1))
                .map(Map.Entry::getKey)
                .forEach(store::remove);
    }

    /**
     * 【将验证码渲染为 SVG 图片并编码为 data URI。
     * SVG 包含：灰色背景矩形、24 个随机位置/颜色的圆形噪点、
     * 4 个随机旋转/位置/颜色的字符文本、3 条随机干扰线。
     * 最终编码为 Base64 的 data:image/svg+xml URI，前端可直接用于 <img src=""> 标签。】
     *
     * @param code 【4 位验证码字符串】
     * @return 【Base64 编码的 SVG data URI，格式为 data:image/svg+xml;base64,...】
     */
    private String renderSvg(String code) {
        var sb = new StringBuilder(512);
        sb.append("<svg xmlns='http://www.w3.org/2000/svg' width='140' height='44' viewBox='0 0 140 44'>");
        sb.append("<rect width='140' height='44' fill='#f3f4f6'/>");
        // 噪点
        // 【绘制 24 个随机位置、随机颜色的圆形噪点，增加 OCR 识别难度】
        for (int i = 0; i < 24; i++) {
            int x = random.nextInt(140);
            int y = random.nextInt(44);
            sb.append("<circle cx='").append(x).append("' cy='").append(y).append("' r='1' fill='#").append(randomHex()).append("'/>");
        }
        // 字符
        // 【逐字符绘制：随机水平偏移 ±3px、随机垂直偏移 ±3px、随机旋转 ±20°、随机颜色，
        // 使字符位置和角度不固定，增加机器识别难度，但人类仍可辨认】
        for (int i = 0; i < code.length(); i++) {
            int x = 18 + i * 28 + random.nextInt(6);
            int y = 30 + random.nextInt(6);
            int rot = random.nextInt(40) - 20;
            sb.append("<text x='").append(x).append("' y='").append(y)
              .append("' font-family='monospace' font-size='26' font-weight='700' fill='#")
              .append(randomHex()).append("' transform='rotate(").append(rot).append(' ').append(x).append(' ').append(y).append(")'>")
              .append(code.charAt(i)).append("</text>");
        }
        // 干扰线
        // 【绘制 3 条随机位置、随机颜色的水平干扰线，进一步增加 OCR 识别难度】
        for (int i = 0; i < 3; i++) {
            sb.append("<line x1='").append(random.nextInt(40)).append("' y1='").append(random.nextInt(44))
              .append("' x2='").append(100 + random.nextInt(40)).append("' y2='").append(random.nextInt(44))
              .append("' stroke='#").append(randomHex()).append("' stroke-width='1'/>");
        }
        sb.append("</svg>");
        return "data:image/svg+xml;base64," + Base64.getEncoder().encodeToString(sb.toString().getBytes(StandardCharsets.UTF_8));
    }

    /**
     * 【生成随机的 6 位十六进制颜色值（如 #3a7bcf）。
     * RGB 各分量范围为 30~129，确保颜色偏深，保证在浅灰背景上的可读性。】
     *
     * @return 【6 位小写十六进制颜色字符串，不带 # 前缀】
     */
    private String randomHex() {
        // 偏深一点的颜色保证可读
        return String.format("%02x%02x%02x", 30 + random.nextInt(100), 30 + random.nextInt(100), 30 + random.nextInt(100));
    }

    /**
     * 【验证码存储条目，包含验证码值、过期时间和已尝试次数。
     * 使用 record 定义保证不可变性（除了 attempts 使用 AtomicInteger 实现原子递增）。】
     *
     * @param code      【验证码字符串，4 位，存储时为原始值（大写字母+数字）】
     * @param expiresAt 【过期时间点，UTC 时间戳，超过此时间验证码失效】
     * @param attempts  【已验证尝试次数，使用 AtomicInteger 保证线程安全】
     */
    private record Entry(String code, Instant expiresAt, AtomicInteger attempts) {
        /**
         * 【便捷构造函数，尝试次数默认初始化为 0】
         */
        Entry(String code, Instant expiresAt) {
            this(code, expiresAt, new AtomicInteger(0));
        }
    }
}

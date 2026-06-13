package com.soulous.auth;

import com.soulous.common.exception.BadRequestException;
import com.soulous.common.exception.UnauthorizedException;
import org.springframework.security.crypto.bcrypt.BCryptPasswordEncoder;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

import java.time.LocalDateTime;
import java.util.Map;

/**
 * 用户核心业务服务。
 * 负责用户的注册、登录、密码管理、角色变更、资料更新等操作。
 * 同时确保每个用户在首次创建时自动关联一个宠物（{@link Pet}）实例。
 *
 * <p>所有写操作均使用 @Transactional 注解保证数据一致性。
 * 密码使用 BCrypt 算法进行单向哈希存储。
 */
@Service
public class UserService {
    /** 用户持久化仓库 */
    private final UserRepository users;
    /** JWT 服务，用于颁发和解析访问令牌 */
    private final JwtService jwt;
    /** BCrypt 密码编码器，使用默认强度（10 轮） */
    private final BCryptPasswordEncoder encoder = new BCryptPasswordEncoder();

    UserService(UserRepository users, JwtService jwt) {
        this.users = users;
        this.jwt = jwt;
    }

    /**
     * 确保用户存在：如果用户已存在且密码未加密（非 BCrypt 格式），则重新加密；
     * 如果用户不存在，则创建新用户并关联宠物。
     * 主要用于系统初始化、数据迁移等场景。
     *
     * @param username    【用户名】
     * @param rawPassword 【明文密码】
     * @param nickname    【用户昵称】
     * @param role        【用户角色】
     * @return 【用户账户实体】
     */
    @Transactional
    public UserAccount ensureUser(String username, String rawPassword, String nickname, UserRole role) {
        return users.findByUsername(username).map(u -> {
            // 【检查密码是否已为 BCrypt 格式（以 "$2" 开头），未加密则重新加密】
            if (!u.password.startsWith("$2")) {
                u.password = encoder.encode(rawPassword);
                return users.save(u);
            }
            return u;
        }).orElseGet(() -> {
            var user = new UserAccount();
            user.username = username;
            user.password = encoder.encode(rawPassword);
            user.nickname = nickname;
            user.role = role;
            users.save(user);
                return user;
        });
    }

    /**
     * 管理员创建用户接口。
     * 进行用户名非空校验、唯一性校验和密码策略校验后创建用户。
     * 昵称为空时默认使用用户名，角色为空时默认为普通用户（USER）。
     *
     * @param username    【用户名，不能为空且不能重复】
     * @param rawPassword 【明文密码，需满足密码策略】
     * @param nickname    【用户昵称，可选】
     * @param role        【用户角色，可选，默认 USER】
     * @return 【创建的用户账户实体】
     * @throws BadRequestException 【当用户名为空、已被占用或密码不满足策略时抛出】
     */
    @Transactional
    public UserAccount createByAdmin(String username, String rawPassword, String nickname, UserRole role) {
        if (username == null || username.isBlank()) throw new BadRequestException("用户名不能为空");
        var trimmed = username.trim();
        users.findByUsername(trimmed).ifPresent(u -> {
            throw new BadRequestException("用户名已被占用");
        });
        PasswordPolicy.validate(rawPassword, trimmed);
        var user = new UserAccount();
        user.username = trimmed;
        user.password = encoder.encode(rawPassword);
        user.nickname = (nickname == null || nickname.isBlank()) ? trimmed : nickname.trim();
        user.role = role == null ? UserRole.USER : role;
        users.save(user);
        return user;
    }

    /**
     * 启动时引导管理员账户。
     * 如果管理员已存在但角色不是 ADMIN，则升级为 ADMIN；
     * 如果不存在，则使用环境变量 SOULOUS_BOOTSTRAP_ADMIN_PASSWORD 中的密码创建管理员。
     * 用于系统首次部署时自动创建超级管理员。
     *
     * @param username    【管理员用户名】
     * @param rawPassword 【管理员明文密码，新创建时必填】
     * @param nickname    【管理员昵称，可选】
     * @return 【管理员用户账户实体】
     * @throws IllegalStateException 【当需要创建新管理员但未配置密码环境变量时抛出】
     */
    @Transactional
    public UserAccount bootstrapAdmin(String username, String rawPassword, String nickname) {
        return users.findByUsername(username).map(u -> {
            if (u.role != UserRole.ADMIN) {
                u.role = UserRole.ADMIN;
                u.updatedAt = LocalDateTime.now();
                return users.save(u);
            }
            return u;
        }).orElseGet(() -> {
            if (rawPassword == null || rawPassword.isBlank()) {
                throw new IllegalStateException(
                        "SOULOUS_BOOTSTRAP_ADMIN_PASSWORD is required to create a new admin '" + username + "'");
            }
            PasswordPolicy.validate(rawPassword, username);
            var user = new UserAccount();
            user.username = username;
            user.password = encoder.encode(rawPassword);
            user.nickname = (nickname == null || nickname.isBlank()) ? username : nickname;
            user.role = UserRole.ADMIN;
            users.save(user);
                return user;
        });
    }

    /**
     * 用户注册接口。
     * 校验用户名非空、长度范围（3-32 字符）、唯一性，以及密码策略，
     * 然后创建用户、关联宠物并颁发 JWT。
     *
     * @param request 【注册请求，包含用户名、密码、昵称和邮箱】
     * @return 【认证响应，包含 JWT 令牌和用户视图信息】
     * @throws BadRequestException 【当用户名为空、长度不合规或已被占用时抛出】
     */
    @Transactional
    public AuthResponse register(RegisterRequest request) {
        if (request.username() == null || request.username().isBlank()) {
            throw new BadRequestException("用户名不能为空");
        }
        var name = request.username().trim();
        if (name.length() < 3 || name.length() > 32) {
            throw new BadRequestException("用户名长度需在 3-32 个字符之间");
        }
        users.findByUsername(name).ifPresent(u -> {
            throw new BadRequestException("用户名已被占用");
        });
        PasswordPolicy.validate(request.password(), name);
        var user = new UserAccount();
        user.username = name;
        user.password = encoder.encode(request.password());
        user.nickname = request.nickname() == null || request.nickname().isBlank() ? name : request.nickname().trim();
        // 【伴侣昵称默认用用户名：注册即给灵魂伴侣一个默认称呼，之后可在设置页自定义】
        user.companionNickname = name;
        user.email = normalizeEmail(request.email());
        users.save(user);
        return new AuthResponse(jwt.issue(user), view(user));
    }

    /**
     * 用户登录接口。
     * 通过用户名查找用户并验证 BCrypt 密码哈希，成功后更新最后活跃时间、
     * 确保宠物存在并颁发 JWT。
     *
     * @param request 【登录请求，包含用户名和密码】
     * @return 【认证响应，包含 JWT 令牌和用户视图信息】
     * @throws UnauthorizedException 【当用户名不存在或密码错误时抛出（统一错误信息防止用户枚举）】
     */
    @Transactional
    public AuthResponse login(LoginRequest request) {
        var user = users.findByUsername(request.username())
                .filter(u -> encoder.matches(request.password(), u.password))
                .orElseThrow(() -> new UnauthorizedException("用户名或密码错误"));
        user.updatedAt = LocalDateTime.now();
        users.save(user);
        return new AuthResponse(jwt.issue(user), view(user));
    }

    /**
     * 通过 JWT 令牌解析并验证用户。
     * 解析令牌中的 userId 和 tokenVersion，校验 tokenVersion 是否匹配
     * （如果不匹配说明令牌已被撤销，例如密码修改后）。
     *
     * @param token 【JWT 访问令牌】
     * @return 【令牌对应的用户账户实体】
     * @throws UnauthorizedException 【当令牌无效、用户不存在或令牌版本不匹配时抛出】
     */
    public UserAccount byToken(String token) {
        JwtService.ParsedToken parsed;
        try {
            parsed = jwt.parse(token);
        } catch (Exception ex) {
            throw new UnauthorizedException("Invalid token");
        }
        var user = users.findById(parsed.userId()).orElseThrow(() -> new UnauthorizedException("Invalid token"));
        if (user.tokenVersion != parsed.tokenVersion()) {
            throw new UnauthorizedException("Token revoked");
        }
        return user;
    }

    /**
     * 刷新访问令牌：为已认证用户重新颁发 JWT。
     * 不涉及刷新令牌轮换，仅生成新的访问令牌。
     *
     * @param user 【当前已认证的用户】
     * @return 【新的认证响应】
     */
    @Transactional
    public AuthResponse refresh(UserAccount user) {
        return new AuthResponse(jwt.issue(user), view(user));
    }

    /**
     * 修改密码接口。
     * 验证当前密码后更新为新密码，同时递增 tokenVersion 使所有旧令牌立即失效。
     * 修改成功后颁发新的 JWT。
     *
     * @param user            【当前已认证的用户】
     * @param currentPassword 【当前密码，用于身份验证】
     * @param newPassword     【新密码，需满足密码策略】
     * @return 【新的认证响应】
     * @throws UnauthorizedException 【当当前密码不正确时抛出】
     */
    @Transactional
    public AuthResponse changePassword(UserAccount user, String currentPassword, String newPassword) {
        if (currentPassword == null || !encoder.matches(currentPassword, user.password)) {
            throw new UnauthorizedException("Current password is incorrect");
        }
        PasswordPolicy.validate(newPassword, user.username);
        var fresh = users.findById(user.id).orElseThrow(() -> new UnauthorizedException("Invalid user"));
        fresh.password = encoder.encode(newPassword);
        fresh.tokenVersion = fresh.tokenVersion + 1;
        fresh.updatedAt = LocalDateTime.now();
        users.save(fresh);
        return new AuthResponse(jwt.issue(fresh), view(fresh));
    }

    /**
     * 管理员修改用户角色接口。
     * 变更角色后递增 tokenVersion，使用户现有的 JWT 立即反映新角色
     * （例如：被降级的管理员无法继续使用旧的管理员令牌）。
     *
     * @param userId 【目标用户 ID】
     * @param role   【新的用户角色】
     * @return 【更新后的用户账户实体】
     * @throws BadRequestException 【当角色为空或用户不存在时抛出】
     */
    @Transactional
    public UserAccount updateRoleByAdmin(Long userId, UserRole role) {
        if (role == null) throw new BadRequestException("role 不能为空");
        var user = users.findById(userId).orElseThrow(() -> new BadRequestException("用户不存在"));
        user.role = role;
        // 【递增 tokenVersion，使现有 JWT 立即反映新角色】
        // Bump token version so existing JWTs immediately reflect the new role
        // (e.g. a demoted admin can't keep using their old admin token).
        user.tokenVersion = user.tokenVersion + 1;
        user.updatedAt = LocalDateTime.now();
        return users.save(user);
    }

    /**
     * 撤销用户的所有令牌（通过递增 tokenVersion）。
     * 用于安全事件响应、强制用户重新登录等场景。
     *
     * @param user 【目标用户】
     */
    @Transactional
    public void revokeAllTokens(UserAccount user) {
        var fresh = users.findById(user.id).orElseThrow(() -> new UnauthorizedException("Invalid user"));
        fresh.tokenVersion = fresh.tokenVersion + 1;
        fresh.updatedAt = LocalDateTime.now();
        users.save(fresh);
    }

    /**
     * 更新用户个人资料（昵称和邮箱）。
     * 仅更新非 null 的字段，支持部分更新。
     *
     * @param user    【当前已认证的用户】
     * @param request 【资料更新请求，包含可选的 nickname 和 email】
     * @return 【更新后的用户账户实体】
     */
    @Transactional
    public UserAccount updateProfile(UserAccount user, ProfileRequest request) {
        if (request.nickname() != null) user.nickname = request.nickname();
        if (request.email() != null) user.email = normalizeEmail(request.email());
        user.updatedAt = LocalDateTime.now();
        return users.save(user);
    }

    /**
     * 【设置伴侣昵称：全局、跨宠物共享的称呼。空白则回退为用户名，最长 32 字符。
     *  从库中重新加载实体以确保数据最新（与 setAvatar 一致）。】
     *
     * @param user 【当前已认证的用户】
     * @param nickname 【新伴侣昵称，空白视为重置为用户名】
     * @return 【更新后的用户账户实体】
     */
    @Transactional
    public UserAccount setCompanionNickname(UserAccount user, String nickname) {
        var fresh = users.findById(user.id).orElseThrow(() -> new UnauthorizedException("Invalid user"));
        var trimmed = nickname == null ? "" : nickname.trim();
        if (trimmed.isBlank()) {
            fresh.companionNickname = fresh.username;
        } else {
            fresh.companionNickname = trimmed.length() > 32 ? trimmed.substring(0, 32) : trimmed;
        }
        fresh.updatedAt = LocalDateTime.now();
        return users.save(fresh);
    }

    /**
     * 【设置 AI 长期记忆开关：关闭后该用户的索引/检索一律空操作（见 RetrievalService 拦截）。
     *  从库重新加载实体以确保数据最新。】
     *
     * @param user    【当前已认证的用户】
     * @param enabled 【是否允许 AI 记住该用户】
     * @return 【更新后的用户账户实体】
     */
    @Transactional
    public UserAccount setAiMemoryEnabled(UserAccount user, boolean enabled) {
        var fresh = users.findById(user.id).orElseThrow(() -> new UnauthorizedException("Invalid user"));
        fresh.aiMemoryEnabled = enabled;
        fresh.updatedAt = LocalDateTime.now();
        return users.save(fresh);
    }

    /**
     * 设置用户头像 URL。
     * 从数据库重新加载用户实体以确保数据最新，然后更新头像地址。
     *
     * @param user      【当前已认证的用户】
     * @param avatarUrl 【头像的 URL 地址】
     * @return 【更新后的用户账户实体】
     */
    @Transactional
    public UserAccount setAvatar(UserAccount user, String avatarUrl) {
        var fresh = users.findById(user.id).orElseThrow(() -> new UnauthorizedException("Invalid user"));
        fresh.avatarUrl = avatarUrl;
        fresh.updatedAt = LocalDateTime.now();
        return users.save(fresh);
    }

    /** 【邮箱格式正则】与前端校验保持一致：非空白、含 @ 与域名点 */
    private static final java.util.regex.Pattern EMAIL_RE =
            java.util.regex.Pattern.compile("^[^\\s@]+@[^\\s@]+\\.[^\\s@]+$");

    /**
     * 规范化并校验邮箱：trim 后空串视为未填写（返回 null）；非空但格式非法则抛 400。
     * 邮箱可选，但一旦填写就必须合法——后续每日提醒邮件依赖它能真正投递。
     *
     * @param email 【原始邮箱输入，可为 null】
     * @return 【规范化后的邮箱，或 null 表示未填写】
     * @throws BadRequestException 【邮箱非空但格式不合法时抛出】
     */
    private static String normalizeEmail(String email) {
        if (email == null) return null;
        var trimmed = email.trim();
        if (trimmed.isEmpty()) return null;
        if (trimmed.length() > 254 || !EMAIL_RE.matcher(trimmed).matches()) {
            throw new BadRequestException("邮箱格式不正确");
        }
        return trimmed;
    }

    /**
     * 将用户账户实体转换为安全的视图对象（Map）。
     * 不暴露密码、tokenVersion 等敏感字段，仅返回前端所需的展示信息。
     * nickname 和 avatarUrl 为 null 时使用默认值（用户名和空字符串）。
     *
     * @param user 【用户账户实体】
     * @return 【包含 id、username、nickname、email、avatarUrl、role 的 Map】
     */
    public Map<String, Object> view(UserAccount user) {
        return Map.of(
                "id", user.id,
                "username", user.username,
                "nickname", user.nickname == null ? user.username : user.nickname,
                "companionNickname", user.companionNickname == null ? user.username : user.companionNickname,
                "email", user.email == null ? "" : user.email,
                "avatarUrl", user.avatarUrl == null ? "" : user.avatarUrl,
                "role", user.role,
                "coinBalance", user.coinBalance,
                "aiMemoryEnabled", user.aiMemoryEnabled
        );
    }
}

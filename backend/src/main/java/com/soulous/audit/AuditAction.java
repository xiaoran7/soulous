package com.soulous.audit;

/**
 * 【审计操作类型常量类，定义 AuditLog.action 字段的标准操作码。
 * 故意设计为常量持有类而非枚举，这样新的操作码可以由调用方自由添加，
 * 无需数据库 schema 迁移或全代码库重新编译。】
 *
 * <p>Canonical action codes for {@link AuditLog#action}. Intentionally a constants
 * holder rather than an enum so new actions can be added by callers without a
 * schema migration or a recompile sweep across the codebase.</p>
 */
public final class AuditAction {
    /** 【私有构造函数，防止实例化】 */
    private AuditAction() {}

    /** 【登录成功】 */
    public static final String LOGIN_SUCCESS = "LOGIN_SUCCESS";

    /** 【登录失败】 */
    public static final String LOGIN_FAILED = "LOGIN_FAILED";

    /** 【用户登出】 */
    public static final String LOGOUT = "LOGOUT";

    /** 【登出所有设备（批量失效 token）】 */
    public static final String LOGOUT_ALL = "LOGOUT_ALL";

    /** 【密码修改】 */
    public static final String PASSWORD_CHANGED = "PASSWORD_CHANGED";

    /** 【刷新令牌重放检测（安全事件）】 */
    public static final String REFRESH_TOKEN_REPLAYED = "REFRESH_TOKEN_REPLAYED";

    /** 【管理员创建用户】 */
    public static final String ADMIN_CREATE_USER = "ADMIN_CREATE_USER";

    /** 【管理员修改用户角色】 */
    public static final String ADMIN_UPDATE_USER_ROLE = "ADMIN_UPDATE_USER_ROLE";
}

package com.soulous.auth;

/**
 * 【用户角色枚举，定义系统中的用户权限等级。
 *  以字符串形式持久化到数据库（{@link jakarta.persistence.EnumType#STRING}），
 *  避免使用 ordinal 导致枚举顺序变更时数据不一致的问题。
 *  当前仅支持两种基础角色，后续可按需扩展（如 MODERATOR 等）。】
 *
 * <p>User roles in the system.</p>
 */
public enum UserRole {
    /** 【普通用户——系统默认角色，注册时自动分配，拥有基本的读写权限】 */
    USER,

    /** 【管理员——拥有系统管理权限，可管理用户、内容等】 */
    ADMIN
}

package com.soulous.auth;

/**
 * 【用户资料更新请求 DTO（Java Record），用于接收 PUT /api/users/me 的请求体。
 *  采用部分更新语义——仅更新请求中提供的非 null 字段，
 *  未提供的字段保持数据库中的原值不变。】
 *
 * <p>Profile update request payload (partial update).</p>
 *
 * @param nickname  【新昵称，可为 null 表示不更新】
 * @param email     【新邮箱地址，可为 null 表示不更新】
 * @param avatarUrl 【新头像 URL，通常由 /avatar 上传接口自动设置，此处可手动覆盖】
 */
public record ProfileRequest(String nickname, String email, String avatarUrl) {}

package com.soulous.pet;

/**
 * 【宠物头像请求记录：用于设置或更新宠物头像的请求体。
 *  使用 Java record 实现不可变数据对象。】
 *
 * @param avatarUrl 【头像图片的 URL 地址，传 null 或空白字符串表示清除头像】
 */
public record PetAvatarRequest(String avatarUrl) {}

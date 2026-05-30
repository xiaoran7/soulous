package com.soulous.auth;

/**
 * 【验证码响应 DTO，使用 Java record 定义。
 * 当验证码功能启用时，由 CaptchaService.issue() 方法生成并返回，
 * 前端拿到后展示图片，用户输入验证码后将 id 和用户输入一起提交。】
 *
 * @param id    【验证码唯一标识，用于后续 verify() 校验；格式为 "自增序号-随机数" 的 base36 编码字符串】
 * @param image 【验证码图片的 data URI（SVG 格式，Base64 编码），可直接用于 HTML <img> 标签的 src 属性】
 */
public record CaptchaResponse(String id, String image) {}

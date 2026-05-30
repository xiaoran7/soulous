package com.soulous.auth;

import com.soulous.common.exception.BadRequestException;

import java.util.Locale;

/**
 * 【密码复杂度策略工具类，用于新用户注册和密码修改时校验密码强度。
 * 采用纯静态方法设计，不可实例化。校验失败时直接抛出 BadRequestException，
 * 异常消息为中文，可直接展示给前端用户。】
 *
 * <p>Password complexity rules for new registrations.
 * Length is also enforced at the DTO layer via @Size; this class adds character-class
 * and contextual rules and exposes a single failure message in Chinese.</p>
 *
 * 【校验规则汇总】：
 *  - 长度 8..72 字符（BCrypt 输入上限为 72 字节）
 *  - 不允许前导/尾随空格，不允许内嵌空格
 *  - 至少包含以下四类字符中的两类：小写字母、大写字母、数字、特殊符号
 *  - 当用户名长度 >= 4 时，密码不得包含用户名（不区分大小写），防止密码与用户名过于相似
 */
public final class PasswordPolicy {

    /** 【密码最小长度，与 BCrypt 无关，仅作为业务规则下限】 */
    public static final int MIN_LENGTH = 8;

    /** 【密码最大长度，BCrypt 算法输入上限为 72 字节，超过将被截断导致安全隐患】 */
    public static final int MAX_LENGTH = 72;

    /** 【私有构造函数，防止工具类被实例化】 */
    private PasswordPolicy() {}

    /**
     * 【校验密码是否符合复杂度策略。
     * 按以下顺序逐项检查，任一不通过即抛出异常：
     * 1. 非空检查
     * 2. 最小长度检查
     * 3. 最大长度检查
     * 4. 空格检查（遍历每个字符）
     * 5. 字符类别多样性检查（至少两类）
     * 6. 用户名包含检查（防止密码与用户名雷同）】
     *
     * @param password 【待校验的明文密码】
     * @param username 【当前用户名，用于检查密码是否包含用户名；可为 null】
     * @throws BadRequestException 【任一校验规则不通过时抛出，消息为中文】
     */
    public static void validate(String password, String username) {
        if (password == null || password.isEmpty()) {
            throw new BadRequestException("密码不能为空");
        }
        if (password.length() < MIN_LENGTH) {
            throw new BadRequestException("密码至少 " + MIN_LENGTH + " 位");
        }
        if (password.length() > MAX_LENGTH) {
            throw new BadRequestException("密码不能超过 " + MAX_LENGTH + " 位");
        }
        // 【逐字符检查是否包含空格，包括制表符、换行等空白字符】
        for (int i = 0; i < password.length(); i++) {
            if (Character.isWhitespace(password.charAt(i))) {
                throw new BadRequestException("密码不能包含空格");
            }
        }
        // 【统计密码中包含的字符类别数量：小写、大写、数字、特殊符号】
        int classes = 0;
        if (containsAny(password, Character::isLowerCase)) classes++;
        if (containsAny(password, Character::isUpperCase)) classes++;
        if (containsAny(password, Character::isDigit)) classes++;
        if (containsAny(password, c -> !Character.isLetterOrDigit(c))) classes++;
        if (classes < 2) {
            throw new BadRequestException("密码至少需要包含字母、数字、符号中的两类");
        }
        // 【当用户名长度 >= 4 时，检查密码是否包含用户名（不区分大小写），
        // 防止用户使用 "username123" 这类过于明显的密码】
        if (username != null && username.length() >= 4
                && password.toLowerCase(Locale.ROOT).contains(username.toLowerCase(Locale.ROOT))) {
            throw new BadRequestException("密码不能包含用户名");
        }
    }

    /**
     * 【辅助方法：检查字符串中是否存在至少一个满足给定谓词的字符。
     * 使用 IntPredicate 函数式接口，支持传入方法引用或 lambda 表达式。】
     *
     * @param s    【待检查的字符串】
     * @param test 【字符匹配谓词，接收 Unicode 码点，返回是否匹配】
     * @return 【如果存在至少一个匹配字符返回 true，否则返回 false】
     */
    private static boolean containsAny(String s, java.util.function.IntPredicate test) {
        for (int i = 0; i < s.length(); i++) {
            if (test.test(s.charAt(i))) return true;
        }
        return false;
    }
}

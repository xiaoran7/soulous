package com.soulous.notification;

/**
 * 【通知类型常量定义类。以 String 形式存储（而非枚举），以便后续新增类型时无需数据库迁移。
 * 但前端根据这些常量匹配图标和文案，因此务必保持稳定不变。】
 *
 * <p>Canonical notification types. Stored as a String column (not enum) so adding
 * new types later doesn't require a schema migration — but the frontend keys
 * its icons/copy off these constants, so keep them stable.</p>
 */
public final class NotificationType {
    /** AI审核完成（通过 / 打回 / 需要补充）。refType=SUBMISSION, refId=submissionId. */
    public static final String AI_REVIEW_DONE = "AI_REVIEW_DONE";
    /** 管理员处理了申诉。refType=APPEAL, refId=appealId. */
    public static final String APPEAL_REVIEWED = "APPEAL_REVIEWED";
    /** 内容被审核系统拦截。refType=SUBMISSION, refId=submissionId. */
    public static final String MODERATION_BLOCKED = "MODERATION_BLOCKED";

    /** 【私有构造器，防止实例化——此类仅作为常量容器】 */
    private NotificationType() {}
}

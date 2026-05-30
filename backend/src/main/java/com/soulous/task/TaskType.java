package com.soulous.task;

/**
 * 【任务类型枚举——定义了系统支持的所有学习任务类型。
 *  每种类型对应不同的任务模板和提交证明要求：
 *  - SIMPLE：简单任务，仅需文本描述即可完成
 *  - STUDY：学习任务，通常需要记录学习时长
 *  - CODING：编程任务，需要提交代码片段作为证明
 *  - NOTE：笔记任务，需要提交笔记内容
 *  - MEMORY：记忆任务，如背诵单词、公式等
 *  - REVIEW：复习任务，需要提交复习总结
 *  由 TaskService 在创建任务时使用，也用于前端表单的条件渲染。】
 *
 * <p>English: Enumerates the supported task types in the system.</p>
 */
public enum TaskType { SIMPLE, STUDY, CODING, NOTE, MEMORY, REVIEW }

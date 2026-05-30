package com.soulous.timetable;

/**
 * 【单双周枚举：描述一门课在学期内的开课周次奇偶性。
 *  教务系统课表里常见"单周"/"双周"标注，影响某一周该课是否真的上。】
 *
 * <ul>
 *   <li>{@link #ALL}  —— 每周都上（默认，未标注单双周时使用）</li>
 *   <li>{@link #ODD}  —— 仅单周上课（第 1、3、5… 周）</li>
 *   <li>{@link #EVEN} —— 仅双周上课（第 2、4、6… 周）</li>
 * </ul>
 */
public enum WeekParity {
    /** 【每周都上（默认）】 */
    ALL,
    /** 【仅单周】 */
    ODD,
    /** 【仅双周】 */
    EVEN
}

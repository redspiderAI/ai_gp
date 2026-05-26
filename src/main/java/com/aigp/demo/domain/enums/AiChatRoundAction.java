package com.aigp.demo.domain.enums;

/**
 * 单轮 AI 对话对助手提醒（user_assistant_tasks）产生的动作摘要，供前端展示本回合发生了什么。
 * <p>未调用助手任务工具或其它能力工具时为 {@link #CHAT_ONLY}。
 */
public enum AiChatRoundAction {
	/** 本轮未调用助手任务相关工具（纯闲聊、成长计划工具等） */
	CHAT_ONLY,
	/** 本轮通过 list_tasks / get_task 查询了提醒/待办 */
	REMINDER_QUERIED,
	/** 本轮通过 create_task 新建了提醒/待办 */
	REMINDER_CREATED,
	/** 本轮通过 update_task 修改了提醒（非完成） */
	REMINDER_UPDATED,
	/** 本轮通过 delete_task 取消/删除了提醒 */
	REMINDER_DELETED,
	/** 本轮通过 update_task 将提醒标记为完成（status=DONE） */
	REMINDER_COMPLETED
}

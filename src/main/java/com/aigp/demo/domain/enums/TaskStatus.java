package com.aigp.demo.domain.enums;

public enum TaskStatus {
	/** 待执行（仅 scheduledDate 当天可点击开始） */
	PENDING,
	/** 执行中（从 started_at 起计时 estimated_minutes） */
	IN_PROGRESS,
	/** 已完成（到时自动结束或手动完成） */
	COMPLETED,
	/** 用户主动跳过 */
	SKIPPED,
	/** 计划日已过仍未开始或未在时限内完成 */
	INCOMPLETE
}

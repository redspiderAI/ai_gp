package com.aigp.demo.service;

/**
 * 首轮规划：本轮对话需要从库中加载哪些数据、是否启用任务工具。
 */
public record AiChatDataPlan(
		boolean needChatHistory,
		boolean needUserProfile,
		boolean needTaskList,
		boolean needTaskTools,
		boolean needPlanProposalTools,
		String taskListStatus,
		String reason) {

	public static AiChatDataPlan defaults() {
		return new AiChatDataPlan(true, true, true, true, false, "OPEN", "默认加载常用上下文");
	}
}

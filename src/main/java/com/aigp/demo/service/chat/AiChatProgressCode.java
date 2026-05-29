package com.aigp.demo.service.chat;

/**
 * 对话进行中推送给前端的进度码（固定话术见 {@link AiChatProgressMessages}）。
 */
public enum AiChatProgressCode {
	SESSION_READY("ROUTING", "会话已就绪"),
	ANALYZING("ROUTING", "正在理解您的问题…"),
	ROUTE_CHAT_ONLY("ROUTING", "正在组织回复…"),
	ROUTE_WITH_TOOLS("ROUTING", "正在为您处理…"),
	EXPECT_CREATE_TASK("ROUTING", "准备为您创建提醒…"),
	EXPECT_LIST_TASKS("ROUTING", "准备查询您的待办…"),
	EXPECT_UPDATE_TASK("ROUTING", "准备更新您的待办…"),
	EXPECT_PLAN("ROUTING", "准备为您制定学习计划…"),
	UNSUPPORTED_FEATURE("ROUTING", "该功能暂未开放…"),
	LOADING_CONTEXT("PREPARING", "正在加载您的待办与资料…"),
	GENERATING("EXECUTING", "正在思考回复…"),
	SYNTHESIZING("EXECUTING", "正在整理回复…"),
	TOOL_LIST_TASKS("EXECUTING", "正在查询您的待办…"),
	TOOL_GET_TASK("EXECUTING", "正在查看任务详情…"),
	TOOL_CREATE_TASK("EXECUTING", "正在为您创建提醒…"),
	TOOL_UPDATE_TASK("EXECUTING", "正在更新您的待办…"),
	TOOL_DELETE_TASK("EXECUTING", "正在取消该待办…"),
	TOOL_LIST_GROWTH_TASKS("EXECUTING", "正在查询今日学习任务…"),
	TOOL_COMPLETE_GROWTH_TASK("EXECUTING", "正在标记任务完成…"),
	TOOL_PROPOSE_PLAN("EXECUTING", "正在为您构建学习计划…");

	private final String phase;
	private final String defaultMessage;

	AiChatProgressCode(String phase, String defaultMessage) {
		this.phase = phase;
		this.defaultMessage = defaultMessage;
	}

	public String phase() {
		return phase;
	}

	public String defaultMessage() {
		return defaultMessage;
	}
}

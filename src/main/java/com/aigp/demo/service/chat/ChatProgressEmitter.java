package com.aigp.demo.service.chat;

/**
 * 对话流水线进度回调：SSE 流式接口注入实现；同步 {@code POST /ai/chat} 使用空实现。
 */
public interface ChatProgressEmitter {

	/** 推送一条固定话术进度（文案由 {@link AiChatProgressMessages} 决定）。 */
	void emit(AiChatProgressCode code);

	/** 推送工具执行进度（按 tool 名查表）。 */
	void emitTool(String toolName, int executeRound, int toolIndexInRound);

	/** 绑定会话 id（新建会话后调用一次）。 */
	void bindSession(Long sessionId);

	/** 是否向客户端推送（noop 为 false）。 */
	boolean isActive();
}

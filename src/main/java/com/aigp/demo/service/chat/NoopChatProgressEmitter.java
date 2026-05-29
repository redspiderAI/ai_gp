package com.aigp.demo.service.chat;

/** 同步对话接口使用：不推送中间进度。 */
public final class NoopChatProgressEmitter implements ChatProgressEmitter {

	public static final NoopChatProgressEmitter INSTANCE = new NoopChatProgressEmitter();

	private NoopChatProgressEmitter() {}

	@Override
	public void emit(AiChatProgressCode code) {}

	@Override
	public void emitTool(String toolName, int executeRound, int toolIndexInRound) {}

	@Override
	public void bindSession(Long sessionId) {}

	@Override
	public boolean isActive() {
		return false;
	}
}

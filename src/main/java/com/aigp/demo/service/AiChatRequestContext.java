package com.aigp.demo.service;

/**
 * 单次 /ai/chat 请求上下文，供工具执行阶段读取用户原话（如推断「未来几天」日期范围）。
 */
public final class AiChatRequestContext {

	private static final ThreadLocal<String> USER_MESSAGE = new ThreadLocal<>();
	private static final ThreadLocal<java.util.List<Long>> MESSAGE_IMAGE_ASSET_IDS = new ThreadLocal<>();
	private static final ThreadLocal<Long> SESSION_ID = new ThreadLocal<>();

	private AiChatRequestContext() {}

	public static void setUserMessage(String message) {
		if (message == null || message.isBlank()) {
			USER_MESSAGE.remove();
		} else {
			USER_MESSAGE.set(message.trim());
		}
	}

	public static String getUserMessage() {
		return USER_MESSAGE.get();
	}

	public static void setMessageImageAssetIds(java.util.List<Long> assetIds) {
		if (assetIds == null || assetIds.isEmpty()) {
			MESSAGE_IMAGE_ASSET_IDS.remove();
		} else {
			MESSAGE_IMAGE_ASSET_IDS.set(java.util.List.copyOf(assetIds));
		}
	}

	public static java.util.List<Long> getMessageImageAssetIds() {
		java.util.List<Long> ids = MESSAGE_IMAGE_ASSET_IDS.get();
		return ids == null ? java.util.List.of() : ids;
	}

	public static void setSessionId(Long sessionId) {
		if (sessionId == null || sessionId <= 0) {
			SESSION_ID.remove();
		} else {
			SESSION_ID.set(sessionId);
		}
	}

	public static Long getSessionId() {
		return SESSION_ID.get();
	}

	public static void clear() {
		USER_MESSAGE.remove();
		MESSAGE_IMAGE_ASSET_IDS.remove();
		SESSION_ID.remove();
	}
}

package com.aigp.demo.domain.enums;

/** 用户任务来源：`tasks` 成长计划表 或 `user_assistant_tasks` 助手待办表。 */
public enum UserTaskSource {
	/** 助手待办 user_assistant_tasks */
	ASSISTANT,
	/** 成长计划 tasks */
	GROWTH;

	public static UserTaskSource parse(String raw) {
		if (raw == null || raw.isBlank()) {
			throw new IllegalArgumentException("source 不能为空，须为 assistant 或 growth");
		}
		String key = raw.trim().toUpperCase();
		if ("USER_ASSISTANT_TASKS".equals(key)) {
			return ASSISTANT;
		}
		if ("TASKS".equals(key)) {
			return GROWTH;
		}
		try {
			return UserTaskSource.valueOf(key);
		} catch (IllegalArgumentException e) {
			throw new IllegalArgumentException("source 须为 assistant 或 growth");
		}
	}
}

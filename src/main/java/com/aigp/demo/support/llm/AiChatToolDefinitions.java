package com.aigp.demo.support.llm;

import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;

public final class AiChatToolDefinitions {

	private AiChatToolDefinitions() {}

	public static List<Map<String, Object>> taskTools() {
		return List.of(
				tool(
						"list_tasks",
						"查询当前用户的任务列表，可按状态与截止日期范围筛选",
						Map.of(
								"type",
								"object",
								"properties",
								Map.of(
										"status",
										Map.of(
												"type",
												"string",
												"enum",
												List.of("OPEN", "DONE", "CANCELLED"),
												"description",
												"可选，按状态筛选"),
										"dueFrom",
										Map.of(
												"type",
												"string",
												"description",
												"可选，截止日期下限 yyyy-MM-dd（含）"),
										"dueTo",
										Map.of(
												"type",
												"string",
												"description",
												"可选，截止日期上限 yyyy-MM-dd（含）")),
								"required",
								List.of())),
				tool(
						"get_task",
						"按任务 ID 查询单条任务详情",
						Map.of(
								"type",
								"object",
								"properties",
								Map.of("taskId", Map.of("type", "integer", "description", "任务 ID")),
								"required",
								List.of("taskId"))),
				tool(
						"create_task",
						"为用户创建一条新任务",
						Map.of(
								"type",
								"object",
								"properties",
								Map.of(
										"title",
										Map.of("type", "string", "description", "任务标题，必填"),
										"description",
										Map.of("type", "string", "description", "任务描述，可选"),
										"dueDate",
										Map.of(
												"type",
												"string",
												"description",
												"仅「某天」无具体时刻的记待办时用 yyyy-MM-dd；提醒类或已有 dueAt 时不要单独填今天"),
										"dueAt",
										Map.of(
												"type",
												"string",
												"description",
												"截止时刻 yyyy-MM-dd HH:mm（用户本地，精确到分）。用户说「提醒我」等但未给几点时必填，并由模型推荐合理时刻"),
										"imageAssetIds",
										Map.of(
												"type",
												"array",
												"items",
												Map.of("type", "integer"),
												"description",
												"可选；仅当用户明确要求把图片记入任务时传入 assetId")),
								"required",
								List.of("title"))),
				tool(
						"update_task",
						"更新用户已有任务（部分字段）",
						Map.of(
								"type",
								"object",
								"properties",
								Map.of(
										"taskId",
										Map.of("type", "integer", "description", "任务 ID"),
										"title",
										Map.of("type", "string"),
										"description",
										Map.of("type", "string"),
										"status",
										Map.of(
												"type",
												"string",
												"enum",
												List.of("OPEN", "DONE", "CANCELLED"),
												"description",
												"可选；用户说任务/待办「完成了」时设为 DONE"),
										"dueDate",
										Map.of("type", "string", "description", "yyyy-MM-dd；空字符串清除日期"),
										"dueAt",
										Map.of(
												"type",
												"string",
												"description",
												"yyyy-MM-dd HH:mm；空字符串清除时刻"),
										"imageAssetIds",
										Map.of(
												"type",
												"array",
												"items",
												Map.of("type", "integer"),
												"description",
												"替换任务附图；传空数组 [] 清除；不传则不改图片")),
								"required",
								List.of("taskId"))),
				tool(
						"delete_task",
						"删除（取消）用户的一条任务，将状态置为 CANCELLED",
						Map.of(
								"type",
								"object",
								"properties",
								Map.of("taskId", Map.of("type", "integer", "description", "任务 ID")),
								"required",
								List.of("taskId"))));
	}

	/** 成长计划 tasks 表：查询与标记完成（用户确认计划后入库的任务）。 */
	public static List<Map<String, Object>> growthTaskTools() {
		return List.of(
				tool(
						"list_growth_tasks",
						"查询用户成长计划每日任务（tasks 表）。用户说「今天的学习任务」或要标记计划任务完成前先调用。",
						Map.of(
								"type",
								"object",
								"properties",
								Map.of(
										"date",
										Map.of(
												"type",
												"string",
												"description",
												"计划执行日 yyyy-MM-dd；省略则用用户本地今天"),
										"status",
										Map.of(
												"type",
												"string",
												"description",
												"可选，按状态筛选：PENDING/IN_PROGRESS/COMPLETED 等")),
								"required",
								List.of())),
				tool(
						"complete_growth_task",
						"将成长计划任务标记为已完成（tasks 表）。仅未完成状态；须先 list_growth_tasks 确认 taskId。",
						Map.of(
								"type",
								"object",
								"properties",
								Map.of(
										"taskId",
										Map.of("type", "integer", "description", "成长计划任务 ID（来自 list_growth_tasks）")),
								"required",
								List.of("taskId"))));
	}

	/**
	 * 成长计划草案工具：仅保存待用户确认的方案，不直接写入 goals/plans/tasks。
	 */
	public static List<Map<String, Object>> planProposalTools() {
		return List.of(
				tool(
						"propose_growth_plan",
						"""
						提交一份结构化成长/学习计划草案（待用户在 App 内确认后才入库）。
						用户要制定复习计划、备考方案、N天学习计划时必须调用本工具；
						禁止用 create_task 批量代替整份计划。
						调用成功后向用户展示 summary 与每日安排要点，并说明需点击确认后才会开始每日提醒。
						""",
						Map.of(
								"type",
								"object",
								"properties",
								Map.of(
										"version",
										Map.of("type", "integer", "description", "固定填 1"),
										"goalTitle",
										Map.of("type", "string", "description", "目标标题，如「一个月通过六级」"),
										"goalDescription",
										Map.of("type", "string", "description", "目标说明，可选"),
										"domain",
										Map.of(
												"type",
												"string",
												"enum",
												List.of(
														"SKILLS",
														"CERTIFICATION",
														"LANGUAGE",
														"SOFT_SKILLS",
														"SIDE_HUSTLE"),
												"description",
												"成长领域，考试类建议 LANGUAGE"),
										"deadline",
										Map.of("type", "string", "description", "目标截止日 yyyy-MM-dd"),
										"summary",
										Map.of("type", "string", "description", "给用户看的计划总述（2～6 句）"),
										"dailyReminderTime",
										Map.of(
												"type",
												"string",
												"description",
												"每日提醒时刻 HH:mm，默认 08:00"),
										"days",
										Map.of(
												"type",
												"array",
												"description",
												"按天的学习任务，最多 31 天",
												"items",
												Map.of(
														"type",
														"object",
														"properties",
														Map.of(
																"dayIndex",
																Map.of(
																		"type",
																		"integer",
																		"description",
																		"第几天，从 1 起"),
																"scheduledDate",
																Map.of(
																		"type",
																		"string",
																		"description",
																		"执行日 yyyy-MM-dd"),
																"title",
																Map.of("type", "string", "description", "当日任务标题"),
																"description",
																Map.of(
																		"type",
																		"string",
																		"description",
																		"当日任务说明"),
																"estimatedMinutes",
																Map.of(
																		"type",
																		"integer",
																		"description",
																		"预计分钟数 15～480")),
														"required",
														List.of(
																"dayIndex",
																"scheduledDate",
																"title",
																"estimatedMinutes")))),
								"required",
								List.of("version", "goalTitle", "summary", "days"))));
	}

	private static Map<String, Object> tool(String name, String description, Map<String, Object> parameters) {
		Map<String, Object> fn = new LinkedHashMap<>();
		fn.put("name", name);
		fn.put("description", description);
		fn.put("parameters", parameters);
		Map<String, Object> tool = new LinkedHashMap<>();
		tool.put("type", "function");
		tool.put("function", fn);
		return tool;
	}
}

package com.aigp.demo.service.chat;

/**
 * 意图分析 LLM 输出的结构化结果（仅允许枚举字段，禁止自由短文）。
 */
public record AiChatStructuredIntent(
		PrimaryGoal primaryGoal,
		AssistantTaskOp assistantTaskOp,
		GrowthTaskOp growthTaskOp,
		PlanOp planOp,
		boolean reminderNeedsDueAt,
		boolean completeNeedsDisambiguation) {

	/** 用户本轮主目标（单选）。 */
	public enum PrimaryGoal {
		CHAT,
		ASSISTANT_TASK,
		GROWTH_PLAN_TASK,
		PLAN_PROPOSAL
	}

	/** 助手待办相关操作（单选）。 */
	public enum AssistantTaskOp {
		NONE,
		LIST,
		GET,
		CREATE,
		UPDATE,
		DELETE,
		COMPLETE_QUERY,
		COMPLETE_MARK
	}

	/** 成长计划 tasks 表操作（单选）。 */
	public enum GrowthTaskOp {
		NONE,
		LIST,
		COMPLETE
	}

	/** 计划草案操作（单选）。 */
	public enum PlanOp {
		NONE,
		PROPOSE
	}

	public boolean suggestsAssistantTaskTools() {
		return assistantTaskOp != AssistantTaskOp.NONE;
	}

	public boolean suggestsGrowthPlanTools() {
		return growthTaskOp != GrowthTaskOp.NONE;
	}

	public boolean suggestsPlanProposalTools() {
		return planOp == PlanOp.PROPOSE;
	}

	public boolean suggestsTaskMutation() {
		return assistantTaskOp == AssistantTaskOp.CREATE
				|| assistantTaskOp == AssistantTaskOp.UPDATE
				|| assistantTaskOp == AssistantTaskOp.DELETE
				|| assistantTaskOp == AssistantTaskOp.COMPLETE_MARK
				|| growthTaskOp == GrowthTaskOp.COMPLETE;
	}

	/**
	 * 转为执行阶段 system 内的固定格式说明（非模型自由发挥原文）。
	 */
	public String toExecuteHintBlock() {
		StringBuilder sb = new StringBuilder();
		sb.append("【结构化意图-仅供执行参考，勿原样复述】\n");
		sb.append("primaryGoal=").append(primaryGoal.name()).append('\n');
		sb.append("assistantTaskOp=").append(assistantTaskOp.name()).append('\n');
		sb.append("growthTaskOp=").append(growthTaskOp.name()).append('\n');
		sb.append("planOp=").append(planOp.name()).append('\n');
		sb.append("reminderNeedsDueAt=").append(reminderNeedsDueAt).append('\n');
		sb.append("completeNeedsDisambiguation=").append(completeNeedsDisambiguation).append('\n');
		sb.append("【执行指令】\n");
		appendExecutionDirectives(sb);
		return sb.toString();
	}

	private void appendExecutionDirectives(StringBuilder sb) {
		switch (planOp) {
			case PROPOSE -> sb.append("- 必须调用 propose_growth_plan；禁止 create_task 批量代替整份计划。\n");
			default -> {}
		}
		switch (assistantTaskOp) {
			case LIST -> sb.append("- 必须调用 list_tasks 后再组织回复；禁止正文伪造工具调用。\n");
			case GET -> sb.append("- 必须调用 get_task。\n");
			case CREATE -> {
				sb.append("- 必须调用 create_task。\n");
				if (reminderNeedsDueAt) {
					sb.append("- 提醒类须填 dueAt（yyyy-MM-dd HH:mm），由你推荐合理时刻，勿仅 dueDate。\n");
				}
			}
			case UPDATE -> sb.append("- 必须调用 update_task。\n");
			case DELETE -> sb.append("- 必须调用 delete_task。\n");
			case COMPLETE_MARK -> sb.append("- 须先 list_tasks 匹配后再 update_task(status=DONE)。\n");
			case COMPLETE_QUERY -> sb.append("- 查询完成状态：先 list_tasks/list_growth_tasks，勿未查就声称已完成。\n");
			default -> {}
		}
		switch (growthTaskOp) {
			case LIST -> sb.append("- 必须调用 list_growth_tasks。\n");
			case COMPLETE -> {
				sb.append("- 须先 list_growth_tasks 再 complete_growth_task。\n");
				if (completeNeedsDisambiguation) {
					sb.append("- 多条候选须追问用户，禁止瞎猜 taskId。\n");
				}
			}
			default -> {}
		}
		if (primaryGoal == PrimaryGoal.CHAT
				&& assistantTaskOp == AssistantTaskOp.NONE
				&& growthTaskOp == GrowthTaskOp.NONE
				&& planOp == PlanOp.NONE) {
			sb.append("- 纯对话：无需调用任务/计划工具。\n");
		}
	}
}

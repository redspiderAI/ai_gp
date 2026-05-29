package com.aigp.demo.service.chat;

import com.aigp.demo.service.AiChatDataPlan;
import com.aigp.demo.support.llm.AiChatToolDefinitions;
import java.util.ArrayList;
import java.util.List;
import java.util.Map;

/**
 * 对话流水线阶段 2：按数据计划选择本轮挂载的工具定义。
 */
public final class AiChatToolSelection {

	private AiChatToolSelection() {}

	/**
	 * @param plan 由路由能力转换的数据加载计划
	 * @return OpenAI 兼容 tools 数组，无工具时为空列表
	 */
	public static List<Map<String, Object>> selectTools(AiChatDataPlan plan) {
		List<Map<String, Object>> tools = new ArrayList<>();
		if (plan.needTaskTools()) {
			tools.addAll(AiChatToolDefinitions.taskTools());
		}
		if (plan.needGrowthPlanTools()) {
			tools.addAll(AiChatToolDefinitions.growthTaskTools());
		}
		if (plan.needPlanProposalTools()) {
			tools.addAll(AiChatToolDefinitions.planProposalTools());
		}
		return tools;
	}

	/** 是否为本轮纯对话（不挂 function tools）。 */
	public static boolean isChatOnly(AiChatDataPlan plan) {
		return !plan.needTaskTools() && !plan.needGrowthPlanTools() && !plan.needPlanProposalTools();
	}
}

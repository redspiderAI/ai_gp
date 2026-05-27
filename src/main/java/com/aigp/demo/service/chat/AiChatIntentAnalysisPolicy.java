package com.aigp.demo.service.chat;

import com.aigp.demo.config.AppProperties;

/**
 * 意图分析 LLM 启用策略：默认跳过以降延迟；仅在制定计划草案等复杂场景或显式配置时调用。
 */
public final class AiChatIntentAnalysisPolicy {

	private AiChatIntentAnalysisPolicy() {}

	/**
	 * 是否在本轮调用意图分析 LLM（用户不可见，结果仅拼入 execute 的 system）。
	 *
	 * @param chatConfig 对话配置
	 * @param route 已 refine 的路由计划
	 * @return true 时 {@link com.aigp.demo.service.AiChatPlanningService#analyzeUserIntent} 会被调用
	 */
	public static boolean shouldAnalyzeIntent(AppProperties.Chat chatConfig, AiChatRoutePlan route) {
		if (chatConfig == null || !chatConfig.isMultiPhaseEnabled() || route == null) {
			return false;
		}
		if (route.skipIntentAnalysis()) {
			return false;
		}
		// 显式开启：恢复旧行为（assistant_tasks / plan_proposal / growth_plan_tasks 均跑 intent）
		if (chatConfig.isIntentAnalysisEnabled()) {
			return route.hasCapability(AiChatCapabilityId.ASSISTANT_TASKS)
					|| route.hasCapability(AiChatCapabilityId.PLAN_PROPOSAL)
					|| route.hasCapability(AiChatCapabilityId.GROWTH_PLAN_TASKS);
		}
		// 默认：仅制定成长计划草案仍走 intent（话术复杂、工具参数多）
		return route.hasCapability(AiChatCapabilityId.PLAN_PROPOSAL);
	}
}

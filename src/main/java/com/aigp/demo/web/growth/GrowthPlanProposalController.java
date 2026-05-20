package com.aigp.demo.web.growth;

import com.aigp.demo.service.growth.GrowthPlanProposalService;
import com.aigp.demo.web.growth.dto.GrowthPlanProposalConfirmResponse;
import com.aigp.demo.web.growth.dto.GrowthPlanProposalDetailResponse;
import com.aigp.demo.web.security.CurrentUser;
import com.aigp.demo.web.security.JwtUserClaims;
import io.swagger.v3.oas.annotations.Operation;
import io.swagger.v3.oas.annotations.security.SecurityRequirement;
import io.swagger.v3.oas.annotations.tags.Tag;
import lombok.RequiredArgsConstructor;
import org.springframework.validation.annotation.Validated;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RestController;

/**
 * 成长计划草案：用户查看 AI 生成的计划并确认/拒绝后入库。
 */
@RestController
@RequestMapping("/api/v1/growth/plan-proposals")
@Validated
@RequiredArgsConstructor
@Tag(name = "成长计划草案", description = "AI 拟定计划 → 用户确认 → 入库与每日提醒")
@SecurityRequirement(name = "bearerAuth")
public class GrowthPlanProposalController {

	private final GrowthPlanProposalService growthPlanProposalService;

	/** [成长计划] 查询草案详情（仅本人；含按天任务列表）。 */
	@GetMapping("/{proposalId}")
	@Operation(summary = "获取计划草案详情")
	public GrowthPlanProposalDetailResponse get(
			@CurrentUser JwtUserClaims user, @PathVariable Long proposalId) {
		if (proposalId == null || proposalId <= 0) {
			throw new IllegalArgumentException("proposalId 无效");
		}
		return growthPlanProposalService.getDetail(user.userId(), proposalId);
	}

	/** [成长计划] 用户确认草案：写入 goals/plans/tasks 并创建每日提醒待办。 */
	@PostMapping("/{proposalId}/confirm")
	@Operation(summary = "确认并执行计划草案")
	public GrowthPlanProposalConfirmResponse confirm(
			@CurrentUser JwtUserClaims user, @PathVariable Long proposalId) {
		if (proposalId == null || proposalId <= 0) {
			throw new IllegalArgumentException("proposalId 无效");
		}
		return growthPlanProposalService.confirm(user.userId(), proposalId);
	}

	/** [成长计划] 用户拒绝草案，不写入业务表。 */
	@PostMapping("/{proposalId}/reject")
	@Operation(summary = "拒绝计划草案")
	public void reject(@CurrentUser JwtUserClaims user, @PathVariable Long proposalId) {
		if (proposalId == null || proposalId <= 0) {
			throw new IllegalArgumentException("proposalId 无效");
		}
		growthPlanProposalService.reject(user.userId(), proposalId);
	}
}

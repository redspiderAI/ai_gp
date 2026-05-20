package com.aigp.demo.domain.growth;

import com.aigp.demo.domain.enums.GrowthPlanProposalStatus;
import com.aigp.demo.domain.user.AppUser;
import jakarta.persistence.Column;
import jakarta.persistence.Entity;
import jakarta.persistence.EnumType;
import jakarta.persistence.Enumerated;
import jakarta.persistence.FetchType;
import jakarta.persistence.GeneratedValue;
import jakarta.persistence.GenerationType;
import jakarta.persistence.Id;
import jakarta.persistence.JoinColumn;
import jakarta.persistence.ManyToOne;
import jakarta.persistence.Table;
import java.time.LocalDateTime;
import lombok.Getter;
import lombok.NoArgsConstructor;
import lombok.Setter;
import org.hibernate.annotations.CreationTimestamp;
import org.hibernate.annotations.UpdateTimestamp;

/**
 * AI 生成的成长计划草案，用户确认前不落 goals/plans/tasks；确认后关联 goal_id、plan_id。
 */
@Entity
@Table(name = "growth_plan_proposals")
@Getter
@Setter
@NoArgsConstructor
public class GrowthPlanProposal {

	@Id
	@GeneratedValue(strategy = GenerationType.IDENTITY)
	private Long id;

	@ManyToOne(fetch = FetchType.LAZY, optional = false)
	@JoinColumn(name = "user_id", nullable = false)
	private AppUser user;

	@Column(name = "session_id")
	private Long sessionId;

	@Enumerated(EnumType.STRING)
	@Column(nullable = false, length = 20)
	private GrowthPlanProposalStatus status = GrowthPlanProposalStatus.PENDING;

	/** 结构化计划 JSON，见 {@code GrowthPlanProposalPayload} */
	@Column(name = "payload_json", nullable = false, columnDefinition = "json")
	private String payloadJson;

	@Column(name = "goal_id")
	private Long goalId;

	@Column(name = "plan_id")
	private Long planId;

	@Column(name = "expires_at")
	private LocalDateTime expiresAt;

	@Column(name = "confirmed_at")
	private LocalDateTime confirmedAt;

	@Column(name = "rejected_at")
	private LocalDateTime rejectedAt;

	@CreationTimestamp
	@Column(name = "created_at", nullable = false, updatable = false)
	private LocalDateTime createdAt;

	@UpdateTimestamp
	@Column(name = "updated_at", nullable = false)
	private LocalDateTime updatedAt;
}

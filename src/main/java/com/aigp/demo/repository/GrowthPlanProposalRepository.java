package com.aigp.demo.repository;

import com.aigp.demo.domain.enums.GrowthPlanProposalStatus;
import com.aigp.demo.domain.growth.GrowthPlanProposal;
import java.util.Optional;
import org.springframework.data.jpa.repository.JpaRepository;

public interface GrowthPlanProposalRepository extends JpaRepository<GrowthPlanProposal, Long> {

	Optional<GrowthPlanProposal> findByIdAndUser_Id(Long id, Long userId);

	Optional<GrowthPlanProposal> findFirstByUser_IdAndStatusOrderByCreatedAtDesc(
			Long userId, GrowthPlanProposalStatus status);
}

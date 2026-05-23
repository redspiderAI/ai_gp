package com.aigp.demo.repository;

import com.aigp.demo.domain.chat.AiChatSession;
import java.util.List;
import java.util.Optional;
import org.springframework.data.domain.Page;
import org.springframework.data.domain.Pageable;
import org.springframework.data.jpa.repository.JpaRepository;
import org.springframework.data.jpa.repository.Query;
import org.springframework.data.repository.query.Param;

public interface AiChatSessionRepository extends JpaRepository<AiChatSession, Long> {

	Optional<AiChatSession> findByIdAndUser_Id(Long id, Long userId);

	Optional<AiChatSession> findFirstByUser_IdAndTitle(Long userId, String title);

	/** 当前用户的会话列表，最近活跃的排在前面 */
	Page<AiChatSession> findByUser_IdOrderByUpdatedAtDesc(Long userId, Pageable pageable);

	/**
	 * 用户最近活跃的非系统会话（排除「任务提醒」「本周回顾」等定时会话）。
	 * {@code title IS NULL} 视为普通用户会话。
	 */
	@Query(
			"""
			SELECT s FROM AiChatSession s
			WHERE s.user.id = :userId
			  AND (s.title IS NULL OR s.title NOT IN :excludedTitles)
			ORDER BY s.updatedAt DESC
			""")
	List<AiChatSession> findLatestUserConversationSessions(
			@Param("userId") Long userId,
			@Param("excludedTitles") List<String> excludedTitles,
			Pageable pageable);
}

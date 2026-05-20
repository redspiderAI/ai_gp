package com.aigp.demo.repository;

import com.aigp.demo.domain.chat.AiChatMessage;
import com.aigp.demo.domain.enums.ChatMessageRole;
import java.time.LocalDateTime;
import java.util.List;
import org.springframework.data.domain.Page;
import org.springframework.data.domain.Pageable;
import org.springframework.data.jpa.repository.JpaRepository;
import org.springframework.data.jpa.repository.Query;
import org.springframework.data.repository.query.Param;

public interface AiChatMessageRepository extends JpaRepository<AiChatMessage, Long> {

	List<AiChatMessage> findBySession_IdOrderByCreatedAtDesc(Long sessionId, Pageable pageable);

	List<AiChatMessage> findBySession_IdOrderByCreatedAtAsc(Long sessionId);

	Page<AiChatMessage> findBySession_IdOrderByCreatedAtAsc(Long sessionId, Pageable pageable);

	/**
	 * 统计时间窗内有对话的用户（排除「任务提醒」自动会话），供周总结扫描。
	 */
	@Query(
			"""
			SELECT DISTINCT s.user.id FROM AiChatMessage m
			JOIN m.session s
			WHERE m.createdAt >= :from AND m.createdAt < :to
			  AND m.role IN :roles
			  AND (s.title IS NULL OR s.title NOT IN :excludeTitles)
			""")
	List<Long> findDistinctUserIdsWithMessagesBetween(
			@Param("from") LocalDateTime fromUtc,
			@Param("to") LocalDateTime toUtc,
			@Param("roles") List<ChatMessageRole> roles,
			@Param("excludeTitles") List<String> excludeSessionTitles);

	@Query(
			"""
			SELECT m FROM AiChatMessage m JOIN FETCH m.session s
			WHERE s.user.id = :userId
			  AND m.createdAt >= :from AND m.createdAt < :to
			  AND m.role IN :roles
			  AND (s.title IS NULL OR s.title NOT IN :excludeTitles)
			ORDER BY m.createdAt ASC
			""")
	List<AiChatMessage> findUserMessagesBetween(
			@Param("userId") Long userId,
			@Param("from") LocalDateTime fromUtc,
			@Param("to") LocalDateTime toUtc,
			@Param("roles") List<ChatMessageRole> roles,
			@Param("excludeTitles") List<String> excludeSessionTitles);
}

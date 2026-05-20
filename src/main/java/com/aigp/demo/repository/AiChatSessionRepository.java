package com.aigp.demo.repository;

import com.aigp.demo.domain.chat.AiChatSession;
import java.util.Optional;
import org.springframework.data.domain.Page;
import org.springframework.data.domain.Pageable;
import org.springframework.data.jpa.repository.JpaRepository;

public interface AiChatSessionRepository extends JpaRepository<AiChatSession, Long> {

	Optional<AiChatSession> findByIdAndUser_Id(Long id, Long userId);

	Optional<AiChatSession> findFirstByUser_IdAndTitle(Long userId, String title);

	/** 当前用户的会话列表，最近活跃的排在前面 */
	Page<AiChatSession> findByUser_IdOrderByUpdatedAtDesc(Long userId, Pageable pageable);
}

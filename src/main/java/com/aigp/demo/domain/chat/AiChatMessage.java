package com.aigp.demo.domain.chat;

import com.aigp.demo.domain.enums.ChatMessageRole;
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

@Entity
@Table(name = "ai_chat_messages")
@Getter
@Setter
@NoArgsConstructor
public class AiChatMessage {

	@Id
	@GeneratedValue(strategy = GenerationType.IDENTITY)
	private Long id;

	@ManyToOne(fetch = FetchType.LAZY, optional = false)
	@JoinColumn(name = "session_id", nullable = false)
	private AiChatSession session;

	@Enumerated(EnumType.STRING)
	@Column(nullable = false, length = 20)
	private ChatMessageRole role;

	@Column(columnDefinition = "TEXT")
	private String content;

	@Column(name = "tool_name", length = 64)
	private String toolName;

	@Column(name = "tool_call_id", length = 64)
	private String toolCallId;

	/** 助手消息本轮动作摘要（与 POST /ai/chat 的 roundAction 一致）；USER 消息为空 */
	@Column(name = "round_action", length = 32)
	private String roundAction;

	@CreationTimestamp
	@Column(name = "created_at", nullable = false, updatable = false)
	private LocalDateTime createdAt;
}

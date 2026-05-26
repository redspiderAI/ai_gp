package com.aigp.demo.web.user.dto;

import com.aigp.demo.domain.user.AppUser;
import io.swagger.v3.oas.annotations.media.Schema;
import java.time.LocalDateTime;

/**
 * 用户资料响应（不含敏感字段）。
 */
@Schema(description = "用户资料")
public record UserProfileResponse(
		@Schema(description = "对外 uid") String uid,
		@Schema(description = "昵称") String nickname,
		@Schema(description = "头像") String avatarUrl,
		@Schema(description = "每周可投入小时数") Integer weeklyHours,
		@Schema(description = "时区") String timezone,
		@Schema(description = "语言偏好") String language,
		@Schema(description = "账户状态：1 正常 2 禁用 3 注销") int status,
		@Schema(description = "注册时间") LocalDateTime createdTime,
		@Schema(description = "最近更新时间") LocalDateTime updatedAt,
		@Schema(description = "已绑定手机号（脱敏），未绑定为 null") String phone,
		@Schema(description = "年龄（周岁）") Integer age,
		@Schema(description = "职业") String occupation,
		@Schema(description = "爱好") String hobbies,
		@Schema(description = "希望探索的专业方向等") String explorationInterests,
		@Schema(description = "是否已完成首次画像填写") boolean onboardingCompleted) {

	public static UserProfileResponse fromEntity(AppUser u, String phoneMaskedOrNull) {
		int st = u.getStatus() == null ? 0 : u.getStatus().intValue();
		Integer wh = u.getWeeklyHours() == null ? null : u.getWeeklyHours().intValue();
		Integer age = u.getProfileAge() == null ? null : u.getProfileAge().intValue();
		return new UserProfileResponse(
				u.getUid(),
				u.getNickname(),
				u.getAvatarUrl(),
				wh,
				u.getTimezone(),
				u.getLanguage(),
				st,
				u.getCreatedAt(),
				u.getUpdatedAt(),
				phoneMaskedOrNull,
				age,
				u.getProfileOccupation(),
				u.getProfileHobbies(),
				u.getProfileExploration(),
				Boolean.TRUE.equals(u.getOnboardingCompleted()));
	}
}

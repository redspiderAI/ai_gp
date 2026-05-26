package com.aigp.demo.web.user.dto;

import io.swagger.v3.oas.annotations.media.Schema;
import jakarta.validation.constraints.Max;
import jakarta.validation.constraints.Min;
import jakarta.validation.constraints.Size;

/**
 * 首次登录画像问卷：部分字段可选，未传的字段保持不变；传空字符串可清空对应文本字段。
 * <p>昵称仅在用户尚未设置时才会写入。
 */
@Schema(description = "首次登录用户画像：年龄、职业、爱好、昵称（未填时）、探索方向")
public record PatchOnboardingRequest(
		@Schema(description = "年龄（周岁）", example = "22") @Min(1) @Max(120) Integer age,
		@Schema(description = "职业，如学生、产品经理、自由职业") @Size(max = 200) String occupation,
		@Schema(description = "爱好") @Size(max = 4000) String hobbies,
		@Schema(description = "昵称；仅当账号尚未设置昵称时生效") @Size(max = 50) String nickname,
		@Schema(description = "希望探索的专业方向、领域等") @Size(max = 4000) String explorationInterests,
		@Schema(description = "是否标记为已完成首次画像；未传表示不修改") Boolean onboardingCompleted) {}

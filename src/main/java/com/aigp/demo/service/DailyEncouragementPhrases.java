package com.aigp.demo.service;

import com.aigp.demo.domain.user.AppUser;
import java.time.LocalDate;
import java.time.ZoneId;
import org.springframework.util.StringUtils;

/**
 * 每日 8 点摘要开头的鼓励语（确定性轮换，不调用大模型）。
 */
public final class DailyEncouragementPhrases {

	private static final String[] PHRASES = {
		"新的一天，从小步开始也很了不起。",
		"你已经在路上了，今天再坚持一点点就好。",
		"把注意力放在下一件小事上，你会比想象中走得更远。",
		"进步不必惊天动地，稳稳完成今天就很棒。",
		"给自己一点耐心，成长本来就需要时间。",
		"今天也值得为自己认真一次。",
		"每一次开始，都是在为未来的自己投票。",
		"别和别人比进度，和昨天的自己比就够了。",
		"困难的日子也会过去，你比困难更有韧性。",
		"先动起来，状态往往会跟着好起来。",
		"你值得被温柔对待，先从对自己温柔开始。",
		"完成比完美更重要，今天先迈出一步。",
		"相信积累的力量，今天也在为明天铺路。",
		"累了可以慢下来，但别轻易放弃方向。",
		"你的努力不会白费，只是有时还没显形。"
	};

	private DailyEncouragementPhrases() {}

	/**
	 * 按用户本地日期选取一句鼓励语，可选带上昵称。
	 */
	public static String pick(AppUser user) {
		ZoneId zone = TaskReminderDueEvaluator.resolveZone(user.getTimezone());
		LocalDate today = LocalDate.now(zone);
		int idx = Math.floorMod(today.getDayOfYear() + (int) (user.getId() % 7), PHRASES.length);
		String phrase = PHRASES[idx];
		String nickname = user.getNickname();
		if (StringUtils.hasText(nickname)) {
			return nickname.trim() + "，" + phrase;
		}
		return phrase;
	}
}

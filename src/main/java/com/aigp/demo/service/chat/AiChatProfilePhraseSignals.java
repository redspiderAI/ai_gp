package com.aigp.demo.service.chat;



import java.util.Locale;

import java.util.regex.Pattern;

import org.springframework.util.StringUtils;



/** 识别用户询问自身身份/画像的话术，用于路由追加 {@link AiChatCapabilityId#USER_PROFILE}。 */

public final class AiChatProfilePhraseSignals {



	private static final Pattern PROFILE_QUERY = Pattern.compile(

			".*(我是谁|我叫什么|我的名字|我的昵称|你知道我是谁|你知道我吗|介绍一下我|介绍我自己|"

					+ "我的资料|我的信息|我的画像|关于我|你还记得我吗|who\\s*am\\s*i|what('s|\\s+is)\\s+my\\s+name).*",

			Pattern.CASE_INSENSITIVE | Pattern.UNICODE_CASE | Pattern.DOTALL);



	private AiChatProfilePhraseSignals() {}



	/** 用户是否在问「我是谁 / 我的昵称 / 我的资料」等需读取 users 画像的问题。 */

	public static boolean looksLikeProfileQuery(String msg) {

		if (!StringUtils.hasText(msg)) {

			return false;

		}

		String t = msg.trim();

		if (PROFILE_QUERY.matcher(t).matches()) {

			return true;

		}

		// 「我的爱好/职业/年龄…」类画像字段询问

		if (t.contains("我的") || t.startsWith("我是")) {

			String lower = t.toLowerCase(Locale.ROOT);

			return lower.contains("昵称")

					|| lower.contains("爱好")

					|| lower.contains("职业")

					|| lower.contains("年龄")

					|| lower.contains("探索")

					|| lower.contains("每周")

					|| lower.contains("小时");

		}

		return false;

	}

}



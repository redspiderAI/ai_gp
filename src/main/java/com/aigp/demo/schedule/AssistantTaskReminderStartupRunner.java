package com.aigp.demo.schedule;

import com.aigp.demo.service.AssistantTaskNearDueScheduleService;
import com.aigp.demo.service.AssistantTaskReminderService;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.boot.ApplicationArguments;
import org.springframework.boot.ApplicationRunner;
import org.springframework.stereotype.Component;

/**
 * 应用启动后补扫一次到期/逾期待办，避免开发环境停服期间错过定时 tick。
 */
@Slf4j
@Component
@RequiredArgsConstructor
public class AssistantTaskReminderStartupRunner implements ApplicationRunner {

	private final AssistantTaskReminderService assistantTaskReminderService;
	private final AssistantTaskNearDueScheduleService assistantTaskNearDueScheduleService;

	@Override
	public void run(ApplicationArguments args) {
		try {
			int sent = assistantTaskReminderService.sendDueReminders("startup");
			if (sent > 0) {
				log.info("启动后补发助手任务提醒 {} 条", sent);
			}
		} catch (Exception e) {
			log.warn("启动后补发助手任务提醒失败: {}", e.getMessage());
		}
		try {
			assistantTaskNearDueScheduleService.rescheduleAllOpenNearDue();
		} catch (Exception e) {
			log.warn("启动后注册近期到点调度失败: {}", e.getMessage());
		}
	}
}

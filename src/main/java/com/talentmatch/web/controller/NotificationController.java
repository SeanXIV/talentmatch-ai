package com.talentmatch.web.controller;

import com.talentmatch.notify.NotificationHistoryService;
import com.talentmatch.notify.NotificationSettingsService;
import com.talentmatch.notify.NotificationTestService;
import com.talentmatch.service.Paging;
import com.talentmatch.web.dto.NotificationResponse;
import com.talentmatch.web.dto.NotificationSettingsRequest;
import com.talentmatch.web.dto.NotificationSettingsResponse;
import com.talentmatch.web.dto.PageResponse;
import com.talentmatch.web.dto.TestNotificationResponse;
import jakarta.validation.constraints.Max;
import jakarta.validation.constraints.Min;
import org.springframework.validation.annotation.Validated;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.PutMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RequestParam;
import org.springframework.web.bind.annotation.RestController;

/** Email notifications (Phase 5, §5.4): settings, a test email and the history. */
@RestController
@RequestMapping("/api/notifications")
@Validated
public class NotificationController {

    private final NotificationSettingsService settingsService;
    private final NotificationTestService testService;
    private final NotificationHistoryService historyService;

    public NotificationController(NotificationSettingsService settingsService, NotificationTestService testService,
                                  NotificationHistoryService historyService) {
        this.settingsService = settingsService;
        this.testService = testService;
        this.historyService = historyService;
    }

    @GetMapping("/settings")
    public NotificationSettingsResponse getSettings() {
        return NotificationSettingsResponse.of(settingsService.get());
    }

    @PutMapping("/settings")
    public NotificationSettingsResponse saveSettings(@RequestBody NotificationSettingsRequest request) {
        return NotificationSettingsResponse.of(settingsService.save(request == null ? null : request.toInput()));
    }

    @PostMapping("/test")
    public TestNotificationResponse test() {
        return TestNotificationResponse.of(testService.sendTest());
    }

    @GetMapping
    public PageResponse<NotificationResponse> list(
            @RequestParam(name = "page", defaultValue = "0")
            @Min(value = 0, message = ApiParams.PAGE_MESSAGE) int page,
            @RequestParam(name = "size", defaultValue = "" + Paging.DEFAULT_SIZE)
            @Min(value = 1, message = ApiParams.SIZE_MESSAGE)
            @Max(value = Paging.MAX_SIZE, message = ApiParams.SIZE_MESSAGE) int size) {
        return PageResponse.of(historyService.list(page, size), NotificationResponse::of);
    }
}

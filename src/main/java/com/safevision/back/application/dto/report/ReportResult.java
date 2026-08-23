package com.safevision.back.application.dto.report;

import java.util.List;

public record ReportResult(
        ReportSummary summary,
        List<CategoryCount> byEppType,
        List<CategoryCount> bySite,
        List<DailyCount> dailyTrend,
        List<ZoneCount> byZone,
        List<WorkerCount> topWorkers,
        NotificationHealth notifications
) {}

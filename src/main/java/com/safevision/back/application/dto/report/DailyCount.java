package com.safevision.back.application.dto.report;

import java.time.LocalDate;

public record DailyCount(LocalDate day, long total) {}

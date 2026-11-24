package com.salomon.intranet.attendance;

import java.time.LocalTime;
import java.util.List;
import java.util.Objects;

/** Daily attendance policy, using the biometric clock's local time in Lima. */
public final class DailyAttendanceRules {
    private static final LocalTime LUNCH_START = LocalTime.of(12, 0);
    private static final LocalTime LUNCH_END = LocalTime.of(15, 0);

    private DailyAttendanceRules() {}

    public record Times(String entryTime, String exitTime, String lunchOutTime, String lunchReturnTime) {}

    private static boolean isLunch(LocalTime time) {
        return !time.isBefore(LUNCH_START) && !time.isAfter(LUNCH_END);
    }

    public static Times classify(List<String> rawTimes) {
        List<LocalTime> times = rawTimes.stream().filter(Objects::nonNull)
            .map(LocalTime::parse).distinct().sorted().toList();
        if (times.isEmpty()) return new Times(null, null, null, null);

        LocalTime entry = times.getFirst();
        LocalTime last = times.getLast();
        LocalTime exit = times.size() > 1 && !isLunch(last) ? last : null;
        List<LocalTime> lunch = times.stream()
            .filter(time -> time.isAfter(entry) && isLunch(time))
            .toList();
        LocalTime lunchOut = lunch.isEmpty() ? null : lunch.getFirst();
        LocalTime lunchReturn = lunch.size() < 2 ? null : lunch.getLast();
        return new Times(format(entry), format(exit), format(lunchOut), format(lunchReturn));
    }

    private static String format(LocalTime time) {
        return time == null ? null : time.format(java.time.format.DateTimeFormatter.ofPattern("HH:mm:ss"));
    }
}

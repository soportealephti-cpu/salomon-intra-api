package com.salomon.intranet.attendance;

import org.junit.jupiter.api.Test;
import java.util.List;
import static org.junit.jupiter.api.Assertions.assertEquals;

class DailyAttendanceRulesTest {
    @Test
    void completeDay() {
        assertEquals(new DailyAttendanceRules.Times("08:00:00", "18:00:00", "12:30:00", "13:30:00"),
            DailyAttendanceRules.classify(List.of("18:00:00", "13:30:00", "08:00:00", "12:30:00")));
    }
    @Test
    void singlePunchOnlyEntry() {
        assertEquals(new DailyAttendanceRules.Times("10:28:03", null, null, null),
            DailyAttendanceRules.classify(List.of("10:28:03")));
    }
    @Test
    void lunchWithoutExit() {
        assertEquals(new DailyAttendanceRules.Times("08:00:00", null, "12:00:00", "15:00:00"),
            DailyAttendanceRules.classify(List.of("08:00:00", "12:00:00", "15:00:00")));
    }
    @Test
    void repeatedSameTimeDoesNotCreateExit() {
        assertEquals(new DailyAttendanceRules.Times("08:00:00", null, null, null),
            DailyAttendanceRules.classify(List.of("08:00:00", "08:00:00")));
    }
    @Test
    void noPunches() {
        assertEquals(new DailyAttendanceRules.Times(null, null, null, null),
            DailyAttendanceRules.classify(List.of()));
    }
    @Test
    void singleLunchPunch() {
        assertEquals(new DailyAttendanceRules.Times("08:00:00", "18:00:00", "12:30:00", null),
            DailyAttendanceRules.classify(List.of("08:00:00", "12:30:00", "18:00:00")));
    }
}

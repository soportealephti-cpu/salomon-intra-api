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
        assertEquals(new DailyAttendanceRules.Times("08:00:00", null, "12:30:00", "18:00:00"),
            DailyAttendanceRules.classify(List.of("08:00:00", "12:30:00", "18:00:00")));
    }

    @Test void lateLunchAndExtraPunch(){
        assertEquals(new DailyAttendanceRules.Times("08:00:00","18:00:00","13:00:00","16:00:00"),
            DailyAttendanceRules.classify(List.of("08:00:00","13:00:00","16:00:00","18:00:00","18:05:00")));
    }
    @Test void manualMissingLunchAdvancesNextPunch(){
        var slots=java.util.Map.of("LUNCH_OUT",new DailyAttendanceRules.Slot("12:30:00",null));
        assertEquals(new DailyAttendanceRules.Times("08:00:00",null,"12:30:00","16:00:00"),
            DailyAttendanceRules.sequence(List.of("08:00:00","16:00:00"),slots,List.of()));
    }
    @Test void correctingEntryConsumesOriginal(){
        var slots=java.util.Map.of("ENTRY",new DailyAttendanceRules.Slot("08:00:00","09:00:00"));
        assertEquals(new DailyAttendanceRules.Times("08:00:00",null,"12:00:00",null),
            DailyAttendanceRules.sequence(List.of("09:00:00","12:00:00"),slots,List.of()));
    }
    @Test void resetExitAllowsNextPunch(){
        var slots=java.util.Map.of("EXIT",new DailyAttendanceRules.Slot(null,"17:00:00"));
        assertEquals(new DailyAttendanceRules.Times("08:00:00","18:00:00","12:00:00","16:00:00"),
            DailyAttendanceRules.sequence(List.of("08:00:00","12:00:00","16:00:00","17:00:00","18:00:00"),slots,List.of("17:00:00")));
    }
    @Test void legacyHistoryKeepsOldClassification(){
        assertEquals(new DailyAttendanceRules.Times("08:00:00","18:00:00","12:30:00",null),
            DailyAttendanceRules.legacyClassify(List.of("08:00:00","12:30:00","18:00:00")));
    }
}

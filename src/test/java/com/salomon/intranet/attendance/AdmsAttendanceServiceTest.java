package com.salomon.intranet.attendance;

import org.junit.jupiter.api.Test;
import org.springframework.web.server.ResponseStatusException;

import java.nio.charset.StandardCharsets;
import java.util.List;

import static org.junit.jupiter.api.Assertions.*;

class AdmsAttendanceServiceTest {
    private static byte[] bytes(String value) {
        return value.getBytes(StandardCharsets.UTF_8);
    }

    @Test
    void parsesZktecoStandardAttlog() {
        List<AdmsAttendanceService.Punch> punches = AdmsAttendanceService.parse(bytes(
            "15\t2026-11-19 14:45:03\t0\t15\t0\t0\t0\r\n"));
        assertEquals(1, punches.size());
        assertEquals("15", punches.get(0).pin());
        assertEquals("2026-11-19T14:45:03", punches.get(0).localTime().toString());
        assertEquals("0", punches.get(0).status());
        assertEquals("15", punches.get(0).verify());
    }

    @Test
    void acceptsMultipleRowsAndOptionalColumns() {
        var punches = AdmsAttendanceService.parse(bytes(
            "1\t2026-11-19 08:00:00\n2\t2026-11-19 17:05:00\t1\t2\t"));
        assertEquals(2, punches.size());
        assertEquals("", punches.get(0).status());
        assertEquals("1", punches.get(1).status());
    }

    @Test
    void rejectsPartialMalformedBatchInsteadOfAcknowledging() {
        assertThrows(ResponseStatusException.class, () -> AdmsAttendanceService.parse(
            bytes("1\t2026-11-19 08:00:00\n2\tinvalid-timestamp\t1\t15")));
    }

    @Test
    void rejectsUnknownOrEmptyData() {
        assertThrows(ResponseStatusException.class, () -> AdmsAttendanceService.parse(bytes("")));
        assertThrows(ResponseStatusException.class, () -> AdmsAttendanceService.parse(bytes("PIN=1")));
    }
}

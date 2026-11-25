package com.salomon.intranet.attendance;

import org.springframework.beans.factory.annotation.Value;
import org.springframework.http.*;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.web.bind.annotation.*;
import org.springframework.web.server.ResponseStatusException;
import java.nio.charset.StandardCharsets;
import java.security.MessageDigest;
import java.util.List;

@RestController
@RequestMapping("/internal/marcaciones")
public class CentralAttendanceController {
    private final JdbcTemplate jdbc;
    private final AdmsAttendanceService attendance;
    private final String token;
    public CentralAttendanceController(JdbcTemplate jdbc, AdmsAttendanceService attendance,
        @Value("${MARCACIONES_CENTRAL_TOKEN:}") String token) {
        this.jdbc=jdbc; this.attendance=attendance; this.token=token;
    }
    private void authorize(String supplied) {
        if(token.isBlank() || supplied==null || !MessageDigest.isEqual(token.getBytes(StandardCharsets.UTF_8),supplied.getBytes(StandardCharsets.UTF_8)))
            throw new ResponseStatusException(HttpStatus.FORBIDDEN);
    }
    private boolean owns(String serial,String pin) {
        return Boolean.TRUE.equals(jdbc.queryForObject("""
            SELECT EXISTS(SELECT 1 FROM intra.attendance_employee_device_ids x
            JOIN intra.biometric_devices d ON d.id=x.device_id
            JOIN intra.attendance_employees e ON e.id=x.employee_id
            WHERE d.serial_number=? AND d.active AND x.device_user_id=? AND e.active)
            """,Boolean.class,serial,pin));
    }
    @GetMapping("/owner")
    public boolean owner(@RequestHeader(value="X-Central-Token",required=false) String supplied,
        @RequestParam String serial,@RequestParam String pin) {
        authorize(supplied); return owns(serial,pin);
    }
    @PostMapping(value="/attlog",produces=MediaType.TEXT_PLAIN_VALUE)
    @org.springframework.transaction.annotation.Transactional
    public String receive(@RequestHeader(value="X-Central-Token",required=false) String supplied,
        @RequestParam String serial,@RequestBody byte[] body) {
        authorize(supplied);
        var punches=AdmsAttendanceService.parse(body);
        for(var punch:punches) if(!owns(serial,punch.pin())) throw new ResponseStatusException(HttpStatus.CONFLICT,"PIN no vinculado");
        return attendance.receive(serial,body);
    }
}

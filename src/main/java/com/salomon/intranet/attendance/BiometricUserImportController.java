package com.salomon.intranet.attendance;

import com.salomon.intranet.auth.StaffAuthController;
import org.springframework.http.HttpStatus;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.transaction.annotation.Transactional;
import org.springframework.web.bind.annotation.*;
import org.springframework.web.server.ResponseStatusException;

import java.util.List;

/** A read-only DATA QUERY USERINFO job, never a device enrollment/write command. */
@RestController
@RequestMapping("/api/v1/attendance/user-import")
public class BiometricUserImportController {
    private final JdbcTemplate jdbc;
    private final StaffAuthController auth;

    public BiometricUserImportController(JdbcTemplate jdbc, StaffAuthController auth) {
        this.jdbc = jdbc;
        this.auth = auth;
    }

    public record ImportStatus(long id, long deviceId, String status, Integer resultCode, long linkedUsers) {}

    private void authorize(String authorization) {
        if (!"SA".equals(auth.me(authorization).role())) {
            throw new ResponseStatusException(HttpStatus.FORBIDDEN, "Acceso restringido");
        }
    }

    @PostMapping("/{deviceId}")
    @Transactional
    public ImportStatus request(@RequestHeader(value = "Authorization", required = false) String authorization,
                                @PathVariable long deviceId) {
        authorize(authorization);
        Integer enabled = jdbc.queryForObject(
            "SELECT count(*) FROM intra.biometric_devices WHERE id = ? AND active = true",
            Integer.class, deviceId);
        if (enabled == null || enabled == 0) {
            throw new ResponseStatusException(HttpStatus.NOT_FOUND, "Biométrico no registrado o deshabilitado");
        }
        Integer ongoing = jdbc.queryForObject("""
            SELECT count(*) FROM intra.biometric_user_sync_commands
            WHERE device_id = ? AND status IN ('pending','sent')
            AND created_at > now() - interval '10 minutes'
            """, Integer.class, deviceId);
        if (ongoing != null && ongoing > 0) {
            throw new ResponseStatusException(HttpStatus.CONFLICT,
                "Ya existe una sincronización pendiente; espera o consulta su estado");
        }
        Long id = jdbc.queryForObject("""
            INSERT INTO intra.biometric_user_sync_commands (device_id) VALUES (?) RETURNING id
            """, Long.class, deviceId);
        return statusFor(deviceId, id);
    }

    @GetMapping("/{deviceId}")
    public ImportStatus latest(@RequestHeader(value = "Authorization", required = false) String authorization,
                               @PathVariable long deviceId) {
        authorize(authorization);
        return statusFor(deviceId, null);
    }

    private ImportStatus statusFor(long deviceId, Long requestedId) {
        List<ImportStatus> items = jdbc.query("""
            SELECT c.id, c.device_id, c.status, c.result_code,
                   (SELECT count(*) FROM intra.attendance_employee_device_ids m
                    WHERE m.device_id = c.device_id) AS linked_users
            FROM intra.biometric_user_sync_commands c
            WHERE c.device_id = ?
            """ + (requestedId == null ? " ORDER BY c.id DESC LIMIT 1" : " AND c.id = ?"),
            ps -> {
                ps.setLong(1, deviceId);
                if (requestedId != null) ps.setLong(2, requestedId);
            },
            (rs, n) -> new ImportStatus(rs.getLong("id"), rs.getLong("device_id"),
                rs.getString("status"), rs.getObject("result_code", Integer.class),
                rs.getLong("linked_users")));
        if (items.isEmpty()) {
            return new ImportStatus(0, deviceId, "idle", null,
                jdbc.queryForObject("""
                    SELECT count(*) FROM intra.attendance_employee_device_ids WHERE device_id = ?
                    """, Long.class, deviceId));
        }
        return items.get(0);
    }
}

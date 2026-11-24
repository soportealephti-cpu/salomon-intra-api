package com.salomon.intranet.attendance;

import com.salomon.intranet.auth.StaffAuthController;
import org.springframework.http.HttpStatus;
import org.springframework.http.MediaType;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestHeader;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RequestParam;
import org.springframework.web.bind.annotation.RestController;
import org.springframework.web.server.ResponseStatusException;

import java.time.Duration;
import java.time.Instant;
import java.time.OffsetDateTime;
import java.util.List;

/** Receptor ADMS y diagnostico de dispositivos. */
@RestController
public class AdmsDiagnosticController {
    private final JdbcTemplate jdbc;
    private final StaffAuthController auth;
    private final AdmsUserSyncService userSync;
    private final AdmsAttendanceService attendance;
    private final AdmsOperlogInboxService operlog;

    public AdmsDiagnosticController(JdbcTemplate jdbc, StaffAuthController auth,
                                    AdmsUserSyncService userSync, AdmsAttendanceService attendance,
                                    AdmsOperlogInboxService operlog) {
        this.jdbc = jdbc;
        this.auth = auth;
        this.userSync = userSync;
        this.attendance = attendance;
        this.operlog = operlog;
    }

    public record DeviceStatus(boolean configured, String serialNumber, boolean recentContact,
                               Instant lastSeenAt, String lastEndpoint, long contactCount,
                               String note) {}

    @GetMapping("/api/v1/attendance/device-status")
    public DeviceStatus status(@RequestHeader(value = "Authorization", required = false) String authorization) {
        var user = auth.me(authorization);
        if (!"SA".equals(user.role())) {
            throw new ResponseStatusException(HttpStatus.FORBIDDEN, "Acceso restringido");
        }
        List<DeviceStatus> rows = jdbc.query("""
            SELECT d.serial_number, c.last_seen_at, c.last_endpoint, COALESCE(c.contact_count, 0) contact_count
            FROM intra.biometric_devices d
            LEFT JOIN intra.biometric_device_contacts c ON c.serial_number = d.serial_number
            WHERE d.active = true ORDER BY d.id DESC LIMIT 1
            """, (rs, row) -> {
                OffsetDateTime last = rs.getObject("last_seen_at", OffsetDateTime.class);
                Instant seen = last == null ? null : last.toInstant();
                return new DeviceStatus(true, rs.getString("serial_number"),
                    seen != null && !seen.isAfter(Instant.now()) &&
                        Duration.between(seen, Instant.now()).toMinutes() < 3,
                    seen, rs.getString("last_endpoint"), rs.getLong("contact_count"),
                    "Vista rápida del dispositivo activo más reciente. Consulta el inventario para ver todos.");
            });
        if (rows.isEmpty()) {
            return new DeviceStatus(false, null, false, null, null, 0,
                "Registra un dispositivo desde Gestión de biométricos.");
        }
        return rows.get(0);
    }

    private void verifyAndRecord(String sn, String endpoint) {
        if (sn == null || sn.length() > 64 || !sn.matches("[A-Za-z0-9_-]{4,64}")) {
            throw new ResponseStatusException(HttpStatus.FORBIDDEN, "Dispositivo no registrado");
        }
        Integer exists = jdbc.queryForObject("""
            SELECT COUNT(*) FROM intra.biometric_devices
            WHERE serial_number = ? AND active = true
            """, Integer.class, sn);
        if (exists == null || exists == 0) {
            throw new ResponseStatusException(HttpStatus.FORBIDDEN, "Dispositivo no registrado");
        }
        jdbc.update("""
            INSERT INTO intra.biometric_device_contacts (serial_number, last_endpoint)
            VALUES (?, ?)
            ON CONFLICT (serial_number) DO UPDATE
            SET last_seen_at = now(), last_endpoint = EXCLUDED.last_endpoint,
                contact_count = intra.biometric_device_contacts.contact_count + 1
            """, sn, endpoint);
    }

    @GetMapping(value = {"/iclock/cdata", "/iclock/cdata.aspx"}, produces = MediaType.TEXT_PLAIN_VALUE)
    public String handshake(@RequestParam(value = "SN", required = false) String serial,
                            @RequestParam(value = "options", required = false) String options) {
        verifyAndRecord(serial, "cdata");
        return "GET OPTION FROM: " + serial + "\n" +
               "Stamp=0\nOpStamp=0\nPhotoStamp=0\nErrorDelay=30\nDelay=10\n" +
               "TransTimes=00:00;14:05\nTransInterval=1\nTransFlag=TransData AttLog\n" +
               "Realtime=1\nEncrypt=0\n";
    }

    @GetMapping(value = {"/iclock/getrequest", "/iclock/getrequest.aspx"}, produces = MediaType.TEXT_PLAIN_VALUE)
    public String heartbeat(@RequestParam(value = "SN", required = false) String serial) {
        verifyAndRecord(serial, "getrequest");
        return userSync.nextCommand(serial);
    }


    @PostMapping(value = {"/iclock/cdata", "/iclock/cdata.aspx"}, produces = MediaType.TEXT_PLAIN_VALUE)
    public String postData(@RequestParam(value = "SN", required = false) String serial,
                           @RequestParam(value = "table", required = false) String table,
                           @RequestBody(required = false) byte[] body) {
        verifyAndRecord(serial, "post-" + (table == null ? "unknown" : table.toLowerCase(java.util.Locale.ROOT)));
        if ("options".equalsIgnoreCase(table)) {
            // ADMS device options notification, not an attendance event.
            // Acknowledge without persisting device or network metadata.
            if (body != null && body.length > 16384) {
                throw new ResponseStatusException(HttpStatus.PAYLOAD_TOO_LARGE, "Opciones demasiado grandes");
            }
            return "OK\n";
        }
        if ("ATTLOG".equalsIgnoreCase(table)) {
            return attendance.receive(serial, body);
        }
        if ("OPERLOG".equalsIgnoreCase(table)) {
            return operlog.receive(serial, body);
        }
        return userSync.receiveUsers(serial, table, body);
    }

    @PostMapping(value = {"/iclock/devicecmd", "/iclock/devicecmd.aspx"}, produces = MediaType.TEXT_PLAIN_VALUE)
    public String commandResult(@RequestParam(value = "SN", required = false) String serial,
                                @RequestParam(value = "ID", required = false) Long id,
                                @RequestParam(value = "Return", required = false) Integer result,
                                @RequestBody(required = false) String body) {
        verifyAndRecord(serial, "devicecmd");
        return userSync.confirm(serial, id, result, body);
    }

    @GetMapping(value = {"/iclock/devicecmd", "/iclock/devicecmd.aspx"}, produces = MediaType.TEXT_PLAIN_VALUE)
    public String commandResultGet(@RequestParam(value = "SN", required = false) String serial,
                                   @RequestParam(value = "CmdID", required = false) Long id,
                                   @RequestParam(value = "Return", required = false) Integer result) {
        verifyAndRecord(serial, "devicecmd");
        return userSync.confirm(serial, id, result, "");
    }
}

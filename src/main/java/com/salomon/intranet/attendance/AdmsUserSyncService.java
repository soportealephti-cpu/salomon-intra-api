package com.salomon.intranet.attendance;

import org.springframework.http.HttpStatus;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;
import org.springframework.web.server.ResponseStatusException;
import java.nio.charset.StandardCharsets;
import java.util.ArrayList;
import java.util.List;
import java.util.regex.Matcher;
import java.util.regex.Pattern;

@Service
public class AdmsUserSyncService {
    private final JdbcTemplate jdbc;
    private final AdmsUserProvisionService provision;
    private final AdmsDeviceAdminService deviceAdmins;
    public AdmsUserSyncService(JdbcTemplate jdbc, AdmsUserProvisionService provision, AdmsDeviceAdminService deviceAdmins) {
        this.jdbc = jdbc; this.provision = provision; this.deviceAdmins = deviceAdmins;
    }
    private static final Pattern PIN = Pattern.compile("(?:^|[\\t\\s])PIN=([^\\s\\t]+)", Pattern.CASE_INSENSITIVE);
    private static final Pattern NAME = Pattern.compile("(?:^|[\\t\\s])Name=(.*?)(?=\\t|\\s(?:Pri|Privilege|Passwd|Card|Grp|TZ|Verify|PIN)=|$)", Pattern.CASE_INSENSITIVE);

    private long activeDevice(String sn) {
        List<Long> ids = jdbc.query("SELECT id FROM intra.biometric_devices WHERE serial_number = ? AND active", ps -> ps.setString(1, sn), (rs,i)->rs.getLong(1));
        if (ids.isEmpty()) throw new ResponseStatusException(HttpStatus.FORBIDDEN, "Dispositivo no registrado");
        return ids.get(0);
    }

    @Transactional
    public String nextCommand(String serial) {
        long deviceId = activeDevice(serial);
        String adminCommand = deviceAdmins.nextCommand(deviceId);
        if (adminCommand != null) return adminCommand;
        String queuedProvision = provision.nextCommand(deviceId);
        if (queuedProvision != null) return queuedProvision;
        List<Long> rows = jdbc.query("""
            UPDATE intra.biometric_user_sync_commands c SET status='sent', sent_at=now()
            WHERE id = (
                SELECT id FROM intra.biometric_user_sync_commands
                WHERE device_id=? AND created_at > now() - interval '10 minutes'
                  AND (status='pending' OR (status='sent' AND sent_at < now() - interval '30 seconds'))
                ORDER BY id LIMIT 1 FOR UPDATE SKIP LOCKED
            ) RETURNING c.id
            """, ps -> ps.setLong(1, deviceId), (rs,i)->rs.getLong(1));
        return rows.isEmpty() ? "OK\n" : "C:" + rows.get(0) + ":DATA QUERY USERINFO\n";
    }

    private record Record(String pin, String name) {}

    @Transactional
    public String receiveUsers(String serial, String table, byte[] body) {
        long deviceId = activeDevice(serial);
        if (table == null || !("USERINFO".equalsIgnoreCase(table) ||
                "USER".equalsIgnoreCase(table) || "OPERLOG".equalsIgnoreCase(table))) {
            throw new ResponseStatusException(HttpStatus.SERVICE_UNAVAILABLE, "Tipo de datos no gestionado");
        }
        if (body == null || body.length == 0 || body.length > 65536) {
            throw new ResponseStatusException(HttpStatus.SERVICE_UNAVAILABLE, "Carga de usuarios vacía o demasiado grande");
        }
        Integer active = jdbc.queryForObject("""
            SELECT count(*) FROM intra.biometric_user_sync_commands
            WHERE device_id=? AND status IN ('sent','done') AND created_at > now() - interval '10 minutes'
            """, Integer.class, deviceId);
        if (active == null || active == 0) {
            throw new ResponseStatusException(HttpStatus.SERVICE_UNAVAILABLE, "No hay consulta de usuarios activa");
        }
        List<Record> records = new ArrayList<>();
        for (String line : new String(body, StandardCharsets.UTF_8).split("\\r?\\n")) {
            String value = line.strip();
            if (value.isEmpty()) continue;
            boolean user = value.regionMatches(true, 0, "USER ", 0, 5) ||
                           value.regionMatches(true, 0, "USERINFO ", 0, 9);
            if ("OPERLOG".equalsIgnoreCase(table) && !user) {
                // Do not store or acknowledge fingerprint/face/other operation records.
                throw new ResponseStatusException(HttpStatus.SERVICE_UNAVAILABLE, "OPERLOG contiene datos no gestionados");
            }
            Matcher pin = PIN.matcher(value);
            Matcher name = NAME.matcher(value);
            if (!pin.find() || !pin.group(1).matches("[A-Za-z0-9._-]{1,64}")) {
                throw new ResponseStatusException(HttpStatus.SERVICE_UNAVAILABLE, "Formato de usuario desconocido");
            }
            String person = name.find() ? name.group(1).trim() : "";
            if (person.isEmpty()) person = "Usuario ZKTeco " + pin.group(1);
            if (person.length() > 160) throw new ResponseStatusException(HttpStatus.SERVICE_UNAVAILABLE, "Nombre demasiado largo");
            records.add(new Record(pin.group(1), person));
        }
        if (records.isEmpty()) throw new ResponseStatusException(HttpStatus.SERVICE_UNAVAILABLE, "Sin usuarios en respuesta");
        for (Record record : records) {
            // Device administrators are not employees; never import them into attendance or payroll.
            if (deviceAdmins.isReservedPin(deviceId, record.pin())) continue;
            Integer found = jdbc.queryForObject("""
                SELECT count(*) FROM intra.attendance_employee_device_ids WHERE device_id=? AND device_user_id=?
                """, Integer.class, deviceId, record.pin());
            if (found != null && found > 0) continue;
            String code = "ZK-" + deviceId + "-" + record.pin();
            List<Long> created = jdbc.query("""
                INSERT INTO intra.attendance_employees(full_name, employee_code)
                VALUES (?, ?) ON CONFLICT (employee_code) DO NOTHING RETURNING id
                """, ps -> { ps.setString(1, record.name()); ps.setString(2, code); }, (rs,i)->rs.getLong(1));
            Long id = created.isEmpty()
                ? jdbc.queryForObject("SELECT id FROM intra.attendance_employees WHERE employee_code=?", Long.class, code)
                : created.get(0);
            jdbc.update("""
                INSERT INTO intra.attendance_employee_device_ids(employee_id,device_id,device_user_id)
                VALUES (?,?,?) ON CONFLICT (device_id,device_user_id) DO NOTHING
                """, id, deviceId, record.pin());
        }
        return "OK\n";
    }

    @Transactional
    public String confirm(String serial, Long commandId, Integer result, String body) {
        long deviceId = activeDevice(serial);
        String raw = body == null ? "" : body;
        if (commandId == null || result == null) {
            Matcher id = Pattern.compile("(?:^|[&\\n\\r])ID=(\\d+)").matcher(raw);
            Matcher ret = Pattern.compile("(?:^|[&\\n\\r])Return=(-?\\d+)").matcher(raw);
            if (id.find() && ret.find()) {
                try {
                    commandId = Long.parseLong(id.group(1));
                    result = Integer.parseInt(ret.group(1));
                } catch (NumberFormatException ex) {
                    throw new ResponseStatusException(HttpStatus.BAD_REQUEST, "Respuesta inválida");
                }
            }
        }
        if (commandId == null || result == null) {
            throw new ResponseStatusException(HttpStatus.SERVICE_UNAVAILABLE, "Confirmación no reconocida");
        }
        if (deviceAdmins.complete(deviceId, commandId, result)) return "OK\n";
        if (provision.complete(deviceId, commandId, result)) return "OK\n";
        jdbc.update("""
            UPDATE intra.biometric_user_sync_commands
            SET status = CASE WHEN ?=0 THEN 'done' ELSE 'failed' END,
                result_code=?, completed_at=now()
            WHERE id=? AND device_id=? AND status IN ('pending','sent')
            """, result, result, commandId, deviceId);
        return "OK\n";
    }
}

package com.salomon.intranet.attendance;

import com.salomon.intranet.auth.StaffAuthController;
import org.springframework.dao.DataIntegrityViolationException;
import org.springframework.http.HttpStatus;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;
import org.springframework.web.server.ResponseStatusException;

import java.util.List;

/** Dispatches an explicit basic USERINFO provisioning request via ADMS.
 * It never transfers biometric credentials or requests a device-wide reset.
 */
@Service
public class AdmsUserProvisionService {
    public static final long COMMAND_OFFSET = 1000000000L;
    private final JdbcTemplate jdbc;
    private final StaffAuthController auth;
    private final AdmsDeviceAdminService deviceAdmins;

    public AdmsUserProvisionService(JdbcTemplate jdbc, StaffAuthController auth, AdmsDeviceAdminService deviceAdmins) {
        this.jdbc = jdbc; this.auth = auth; this.deviceAdmins = deviceAdmins;
    }

    private void requireSA(String bearer) {
        if (!"SA".equals(auth.me(bearer).role())) {
            throw new ResponseStatusException(HttpStatus.FORBIDDEN, "Acceso restringido");
        }
    }

    public record ProvisionStatus(long id, long employeeId, long deviceId, String pin,
                                  String status, Integer resultCode) {}
    public record ProvisionRequest(long deviceId, String expectedOldPin) {
        public ProvisionRequest(long deviceId) { this(deviceId, null); }
    }
    private record Payload(long id, long deviceId, long employeeId, String pin, String name) {}

    @Transactional
    public ProvisionStatus enqueue(String authorization, long employeeId, ProvisionRequest request) {
        requireSA(authorization);
        if (request == null || request.deviceId() < 1) {
            throw new ResponseStatusException(HttpStatus.BAD_REQUEST, "Selecciona un biométrico válido");
        }
        List<String[]> workers = jdbc.query("""
            SELECT full_name, dni FROM intra.attendance_employees
            WHERE id=? AND active=true AND archived_at IS NULL FOR UPDATE
            """, ps -> ps.setLong(1, employeeId),
            (rs,i)->new String[]{rs.getString(1), rs.getString(2)});
        if (workers.isEmpty()) {
            throw new ResponseStatusException(HttpStatus.NOT_FOUND, "Trabajador no encontrado o inactivo");
        }
        String name = workers.get(0)[0];
        String pin = workers.get(0)[1];
        if (pin == null || !pin.matches("[0-9]{8}")) {
            throw new ResponseStatusException(HttpStatus.BAD_REQUEST,
                "Registra el DNI de ocho dígitos del trabajador antes de enviarlo al reloj");
        }
        // Only plain basic names can be safely serialized in the line protocol.
        if (name.length() > 48 || !name.matches("[\\p{L}0-9 .'-]{1,48}")) {
            throw new ResponseStatusException(HttpStatus.BAD_REQUEST,
                "Para enviar al reloj, utiliza un nombre de hasta 48 caracteres, sin símbolos especiales");
        }
        Integer active = jdbc.queryForObject("SELECT count(*) FROM intra.biometric_devices WHERE id=? AND active",
            Integer.class, request.deviceId());
        if (active == null || active != 1) {
            throw new ResponseStatusException(HttpStatus.NOT_FOUND, "Biométrico no disponible");
        }
        if (deviceAdmins.isReservedPin(request.deviceId(), pin)) {
            throw new ResponseStatusException(HttpStatus.CONFLICT, "PIN reservado para administrador del biométrico");
        }
        // Do not let provisioning overwrite the PIN of a different employee.
        Integer collision = jdbc.queryForObject("""
            SELECT count(*) FROM intra.attendance_employee_device_ids
            WHERE device_id=? AND device_user_id=? AND employee_id<>?
            """, Integer.class, request.deviceId(), pin, employeeId);
        Integer queuedCollision = jdbc.queryForObject("""
            SELECT count(*) FROM intra.biometric_user_provision_commands
            WHERE device_id=? AND device_user_id=? AND employee_id<>?
              AND status IN ('pending','sent')
            """, Integer.class, request.deviceId(), pin, employeeId);
        if ((collision != null && collision > 0) || (queuedCollision != null && queuedCollision > 0)) {
            throw new ResponseStatusException(HttpStatus.CONFLICT, "Ese PIN ya está asignado a otro trabajador");
        }
        // A repeat click must not create a duplicate or hide a pending command.
        var open = jdbc.query("""
            SELECT id,employee_id,device_id,device_user_id,status,result_code
            FROM intra.biometric_user_provision_commands
            WHERE employee_id=? AND device_id=? AND status IN ('pending','sent')
            ORDER BY id DESC LIMIT 1
            """, (rs,i)->new ProvisionStatus(rs.getLong(1),rs.getLong(2),rs.getLong(3),
                rs.getString(4),rs.getString(5),rs.getObject(6,Integer.class)),
            employeeId,request.deviceId());
        if(!open.isEmpty() && !pin.equals(open.get(0).pin()))
            throw new ResponseStatusException(HttpStatus.CONFLICT,"Existe una orden para otro PIN; consulta su estado");
        if(Boolean.TRUE.equals(jdbc.queryForObject("""
            SELECT EXISTS(SELECT 1 FROM intra.device_removal_commands
            WHERE employee_id=? AND device_id=? AND status IN ('pending','sent'))
            """,Boolean.class,employeeId,request.deviceId())))
            throw new ResponseStatusException(HttpStatus.CONFLICT,"Hay una eliminación pendiente");
        var previous = jdbc.query("""
            SELECT device_user_id FROM intra.attendance_employee_device_ids
            WHERE device_id=? AND employee_id=? AND device_user_id<>? FOR UPDATE
            """,(rs,i)->rs.getString(1),request.deviceId(),employeeId,pin);
        if(!previous.isEmpty()) {
            String oldPin=previous.get(0);
            if(!oldPin.equals(request.expectedOldPin()))
                throw new ResponseStatusException(HttpStatus.PRECONDITION_FAILED,"Confirma que eliminaste el PIN anterior del reloj");
            // Attendance uses the device/PIN link for historical attribution. Never unlink history.
            if(Boolean.TRUE.equals(jdbc.queryForObject("""
                SELECT EXISTS(SELECT 1 FROM intra.biometric_attendance_events
                WHERE device_id=? AND device_user_id=?)
                """,Boolean.class,request.deviceId(),oldPin)))
                throw new ResponseStatusException(HttpStatus.UNPROCESSABLE_ENTITY,"El PIN anterior tiene marcaciones históricas");
            int removed=jdbc.update("""
                DELETE FROM intra.attendance_employee_device_ids m
                WHERE m.employee_id=? AND m.device_id=? AND m.device_user_id=?
                AND NOT EXISTS(SELECT 1 FROM intra.biometric_attendance_events e
                  WHERE e.device_id=m.device_id AND e.device_user_id=m.device_user_id)
                """,employeeId,request.deviceId(),oldPin);
            if(removed!=1)throw new ResponseStatusException(HttpStatus.CONFLICT,"La vinculación cambió; actualiza la lista");
        }
        if(!open.isEmpty())return open.get(0);
        try {
            Long id = jdbc.queryForObject("""
                INSERT INTO intra.biometric_user_provision_commands
                  (employee_id,device_id,device_user_id,requested_name)
                VALUES (?,?,?,?) RETURNING id
                """, Long.class, employeeId, request.deviceId(), pin, name);
            return new ProvisionStatus(id, employeeId, request.deviceId(), pin, "pending", null);
        } catch (DataIntegrityViolationException ex) {
            throw new ResponseStatusException(HttpStatus.CONFLICT,
                "Ya hay una orden pendiente de envío para este trabajador y reloj");
        }
    }


    public record BulkProvisionRequest(long deviceId) {}
    public record BulkProvisionResult(long eligible,long queued,long pending,long sent,long confirmed,long failed) {}

    @Transactional
    public BulkProvisionResult enqueueBulk(String authorization,BulkProvisionRequest request) {
        requireSA(authorization);
        if(request==null || request.deviceId()<1)
            throw new ResponseStatusException(HttpStatus.BAD_REQUEST,"Selecciona un biométrico");
        long deviceId=request.deviceId();
        Integer available=jdbc.queryForObject(
            "SELECT count(*) FROM intra.biometric_devices WHERE id=? AND active",
            Integer.class,deviceId);
        if(available==null || available!=1)
            throw new ResponseStatusException(HttpStatus.NOT_FOUND,"Biométrico no disponible");

        String eligibleSql="""
            SELECT e.id,e.dni,e.full_name FROM intra.attendance_employees e
            WHERE e.active=true AND e.archived_at IS NULL
              AND e.dni ~ '^[0-9]{8}$'
              AND length(e.full_name) BETWEEN 1 AND 48
              AND e.full_name ~ '^[[:alpha:]0-9 .''-]+$'
              AND NOT EXISTS (
                SELECT 1 FROM intra.attendance_employee_device_ids link
                WHERE link.device_id=? AND link.employee_id=e.id
              )
              AND NOT EXISTS (
                SELECT 1 FROM intra.attendance_employee_device_ids link
                WHERE link.device_id=? AND link.device_user_id=e.dni
              )
              AND NOT EXISTS (
                SELECT 1 FROM intra.biometric_device_admins admin
                WHERE admin.device_id=? AND admin.pin=e.dni
              )
              AND NOT EXISTS (
                SELECT 1 FROM intra.biometric_user_provision_commands q
                WHERE q.device_id=? AND q.device_user_id=e.dni
                  AND q.employee_id<>e.id AND q.status IN ('pending','sent')
              )
            """;
        var candidates=jdbc.query(eligibleSql,(rs,i)->new Payload(
            0L,deviceId,rs.getLong("id"),rs.getString("dni"),rs.getString("full_name")),
            deviceId,deviceId,deviceId,deviceId);
        long queued=0;
        for(Payload candidate:candidates) {
            int changed=jdbc.update("""
                INSERT INTO intra.biometric_user_provision_commands
                    (employee_id,device_id,device_user_id,requested_name)
                VALUES(?,?,?,?)
                ON CONFLICT DO NOTHING
                """,candidate.employeeId(),deviceId,candidate.pin(),candidate.name());
            queued+=changed;
        }
        return bulkStatus(authorization,deviceId,candidates.size(),queued);
    }

    public BulkProvisionResult bulkStatus(String authorization,long deviceId) {
        return bulkStatus(authorization,deviceId,0,0);
    }

    private BulkProvisionResult bulkStatus(String authorization,long deviceId,long eligible,long queued) {
        requireSA(authorization);
        List<long[]> stats=jdbc.query("""
            SELECT count(*) FILTER (WHERE status='pending') AS pending,
                   count(*) FILTER (WHERE status='sent') AS sent,
                   count(*) FILTER (WHERE status='confirmed') AS confirmed,
                   count(*) FILTER (WHERE status='failed') AS failed
            FROM (
                SELECT DISTINCT ON(employee_id) employee_id,status
                FROM intra.biometric_user_provision_commands
                WHERE device_id=? ORDER BY employee_id,id DESC
            ) latest
            """,(rs,i)->new long[]{rs.getLong("pending"),rs.getLong("sent"),
                rs.getLong("confirmed"),rs.getLong("failed")},deviceId);
        long[] values=stats.get(0);
        return new BulkProvisionResult(eligible,queued,values[0],values[1],values[2],values[3]);
    }

    public ProvisionStatus latest(String authorization, long employeeId, long deviceId) {
        requireSA(authorization);
        List<ProvisionStatus> rows = jdbc.query("""
            SELECT id, employee_id, device_id, device_user_id, status, result_code
            FROM intra.biometric_user_provision_commands
            WHERE employee_id=? AND device_id=? ORDER BY id DESC LIMIT 1
            """, ps -> { ps.setLong(1, employeeId); ps.setLong(2, deviceId); },
            (rs,i)->new ProvisionStatus(rs.getLong(1), rs.getLong(2), rs.getLong(3),
                rs.getString(4), rs.getString(5), rs.getObject(6,Integer.class)));
        return rows.isEmpty() ? new ProvisionStatus(0, employeeId, deviceId, "", "idle", null) : rows.get(0);
    }

    @Transactional
    public String nextCommand(long deviceId) {
        jdbc.update("""
            UPDATE intra.biometric_user_provision_commands SET status='expired', completed_at=now()
            WHERE device_id=? AND status IN ('pending','sent') AND created_at < now()-interval '24 hours'
            """, deviceId);
        List<Payload> rows = jdbc.query("""
            UPDATE intra.biometric_user_provision_commands q
            SET status='sent', sent_at=now()
            WHERE id=(
              SELECT id FROM intra.biometric_user_provision_commands
              WHERE device_id=? AND
                (status='pending' OR (status='sent' AND sent_at < now()-interval '60 seconds'))
              ORDER BY id LIMIT 1 FOR UPDATE SKIP LOCKED
            )
            RETURNING q.id,q.device_id,q.employee_id,q.device_user_id,q.requested_name
            """, ps -> ps.setLong(1,deviceId),
            (rs,i)->new Payload(rs.getLong(1),rs.getLong(2),rs.getLong(3),rs.getString(4),rs.getString(5)));
        if (rows.isEmpty()) return null;
        Payload row = rows.get(0);
        return "C:" + (COMMAND_OFFSET + row.id()) + ":DATA UPDATE USERINFO PIN=" + row.pin() +
            "\tName=" + row.name() + "\tPri=0\n";
    }

    /** Returns true if the command belongs to the provisioning queue. */
    @Transactional
    public boolean complete(long deviceId, long commandId, int returnCode) {
        if (commandId < COMMAND_OFFSET) return false;
        long id = commandId - COMMAND_OFFSET;
        List<Payload> rows = jdbc.query("""
            SELECT id,device_id,employee_id,device_user_id,requested_name
            FROM intra.biometric_user_provision_commands
            WHERE id=? AND device_id=? AND status IN ('sent','confirmed')
            FOR UPDATE
            """, ps -> { ps.setLong(1,id); ps.setLong(2,deviceId); },
            (rs,i)->new Payload(rs.getLong(1),rs.getLong(2),rs.getLong(3),rs.getString(4),rs.getString(5)));
        if (rows.isEmpty()) return true;
        Payload request = rows.get(0);
        if (returnCode == 0) {
            // Do not reassign a PIN away from an unrelated employee.
            Integer collisions = jdbc.queryForObject("""
                SELECT count(*) FROM intra.attendance_employee_device_ids
                WHERE device_id=? AND device_user_id=? AND employee_id<>?
                """, Integer.class, deviceId, request.pin(), request.employeeId());
            if (collisions != null && collisions > 0) {
                throw new ResponseStatusException(HttpStatus.CONFLICT, "PIN ya vinculado a otro trabajador");
            }
            jdbc.update("""
                INSERT INTO intra.attendance_employee_device_ids(employee_id,device_id,device_user_id)
                VALUES (?,?,?)
                ON CONFLICT (device_id,device_user_id) DO NOTHING
                """, request.employeeId(),deviceId,request.pin());
        }
        jdbc.update("""
            UPDATE intra.biometric_user_provision_commands
            SET status=?,result_code=?,completed_at=now() WHERE id=? AND status='sent'
            """, returnCode == 0 ? "confirmed" : "failed",returnCode,id);
        return true;
    }
}

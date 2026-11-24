package com.salomon.intranet.attendance;

import com.salomon.intranet.auth.StaffAuthController;
import com.salomon.intranet.auth.EmployeeWebAccountController;
import org.springframework.dao.DataIntegrityViolationException;
import org.springframework.http.HttpStatus;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.transaction.annotation.Transactional;
import org.springframework.web.bind.annotation.*;
import org.springframework.web.server.ResponseStatusException;

import java.util.List;
import java.util.Set;

@RestController
@RequestMapping("/api/v1/attendance/employees")
public class AttendanceEmployeesController {
    private final JdbcTemplate jdbc;
    private final StaffAuthController auth;
    private final EmployeeWebAccountController webAccounts;

    public AttendanceEmployeesController(JdbcTemplate jdbc, StaffAuthController auth, EmployeeWebAccountController webAccounts) {
        this.jdbc = jdbc;
        this.auth = auth;
        this.webAccounts = webAccounts;
    }

    public record Employee(long id, String fullName, String employeeCode, String area,
                           String company, String dni, boolean active, Long deviceId,
                           String deviceName, String deviceUserId,
                           String registeredMethods, String observedModes) {}
    public record Input(String fullName, String employeeCode, String area, String company, String dni,
                        Boolean active, Long deviceId, String deviceUserId,
                        List<String> authenticationMethods, EmployeeWebAccountController.Input webAccount) {}

    private void requireSA(String authorization) {
        if (!"SA".equals(auth.me(authorization).role())) {
            throw new ResponseStatusException(HttpStatus.FORBIDDEN, "Acceso restringido");
        }
    }

    private static String trim(String input) {
        return input == null ? null : input.trim();
    }

    private void updateMethods(long employeeId, List<String> methods) {
        if (methods == null) return;
        jdbc.update("DELETE FROM intra.attendance_employee_auth_methods WHERE employee_id = ?", employeeId);
        for (String method : Set.copyOf(methods)) {
            jdbc.update("INSERT INTO intra.attendance_employee_auth_methods (employee_id, method) VALUES (?, ?)",
                employeeId, method);
        }
    }

    private void validate(Input input) {
        if (input == null || input.fullName() == null || input.fullName().isBlank() ||
            input.fullName().trim().length() > 160 ||
            (input.employeeCode() != null && !input.employeeCode().isBlank() &&
             !input.employeeCode().trim().matches("[A-Za-z0-9._-]{1,64}")) ||
            (input.dni() != null && !input.dni().trim().isEmpty() && !input.dni().trim().matches("[0-9]{8}")) ||
            (input.area() != null && input.area().trim().length() > 120) ||
            (input.company() != null && input.company().trim().length() > 160) ||
            (input.deviceId() == null && input.deviceUserId() != null && !input.deviceUserId().isBlank()) ||
            (input.deviceId() != null && (input.deviceId() < 1 ||
                input.deviceUserId() == null || input.deviceUserId().isBlank() ||
                input.deviceUserId().trim().length() > 64)) ||
            (input.authenticationMethods() != null &&
                (input.authenticationMethods().size() > 4 ||
                 input.authenticationMethods().stream().anyMatch(m ->
                     m == null || !Set.of("FACE", "FINGERPRINT", "PASSWORD", "CARD").contains(m))))) {
            throw new ResponseStatusException(HttpStatus.BAD_REQUEST, "Datos del empleado inválidos");
        }
        if (input.deviceId() != null) {
            Integer deviceCount = jdbc.queryForObject(
                "SELECT count(*) FROM intra.biometric_devices WHERE id = ? AND active = true",
                Integer.class, input.deviceId());
            if (deviceCount == null || deviceCount == 0) {
                throw new ResponseStatusException(HttpStatus.BAD_REQUEST, "Biométrico no existe o está deshabilitado");
            }
        }
    }

    @GetMapping
    public List<Employee> list(@RequestHeader(value = "Authorization", required = false) String authorization) {
        requireSA(authorization);
        return jdbc.query("""
            SELECT e.id, e.full_name, e.employee_code, e.area, e.company, e.dni, e.active,
                   link.device_id, d.name AS device_name, link.device_user_id,
                   (SELECT string_agg(m.method, ',' ORDER BY m.method)
                    FROM intra.attendance_employee_auth_methods m
                    WHERE m.employee_id = e.id) AS registered_methods,
                   (SELECT string_agg(DISTINCT
                       CASE p.verify_mode
                         WHEN '1' THEN 'FINGERPRINT'
                         WHEN '15' THEN 'FACE'
                         WHEN '0' THEN 'PASSWORD'
                         WHEN '4' THEN 'CARD'
                         WHEN '20' THEN 'FACE+FINGERPRINT'
                         ELSE 'CODE:' || p.verify_mode END, ',')
                    FROM intra.biometric_attendance_events p
                    WHERE p.device_id = link.device_id
                      AND p.device_user_id = link.device_user_id) AS observed_modes
            FROM intra.attendance_employees e
            LEFT JOIN LATERAL (
              SELECT device_id, device_user_id
              FROM intra.attendance_employee_device_ids
              WHERE employee_id = e.id
              ORDER BY device_id LIMIT 1
            ) link ON true
            LEFT JOIN intra.biometric_devices d ON d.id = link.device_id
            WHERE e.archived_at IS NULL
            ORDER BY e.full_name, e.id
            """, (rs, n) -> new Employee(rs.getLong("id"),
                rs.getString("full_name"), rs.getString("employee_code"),
                rs.getString("area"), rs.getString("company"), rs.getString("dni"), rs.getBoolean("active"),
                rs.getObject("device_id", Long.class), rs.getString("device_name"),
                rs.getString("device_user_id"),
                rs.getString("registered_methods"), rs.getString("observed_modes")));
    }

    @PostMapping
    @ResponseStatus(HttpStatus.CREATED)
    @Transactional
    public Employee create(@RequestHeader(value = "Authorization", required = false) String authorization,
                           @RequestBody Input input) {
        requireSA(authorization);
        validate(input);
        if (input.dni() == null || !input.dni().trim().matches("[0-9]{8}")) {
            throw new ResponseStatusException(HttpStatus.BAD_REQUEST, "DNI obligatorio");
        }
        try {
            Long id = jdbc.queryForObject("""
                INSERT INTO intra.attendance_employees
                (full_name, employee_code, area, company, dni, active)
                VALUES (?, ?, ?, ?, ?, ?) RETURNING id
                """, Long.class, trim(input.fullName()), "DNI-" + input.dni().trim(),
                trim(input.area()), trim(input.company()),
                input.dni() == null || input.dni().isBlank() ? null : input.dni().trim(),
                input.active() == null || input.active());
            if (input.deviceId() != null) {
                jdbc.update("""
                    INSERT INTO intra.attendance_employee_device_ids (employee_id, device_id, device_user_id)
                    VALUES (?, ?, ?)
                    """, id, input.deviceId(), trim(input.deviceUserId()));
            }
            updateMethods(id, input.authenticationMethods());
            if (input.webAccount() != null) webAccounts.save(authorization, id, input.webAccount());
            return list(authorization).stream().filter(e -> e.id() == id).findFirst().orElseThrow();
        } catch (DataIntegrityViolationException ex) {
            throw new ResponseStatusException(HttpStatus.CONFLICT, "DNI, código de trabajador o ID biométrico ya registrado");
        }
    }

    @PutMapping("/{id}")
    @Transactional
    public Employee update(@RequestHeader(value = "Authorization", required = false) String authorization,
                           @PathVariable long id, @RequestBody Input input) {
        requireSA(authorization);
        validate(input);
        if (input.dni() == null || !input.dni().trim().matches("[0-9]{8}")) {
            throw new ResponseStatusException(HttpStatus.BAD_REQUEST, "El DNI debe tener exactamente ocho dígitos");
        }
        try {
            int changed = jdbc.update("""
                UPDATE intra.attendance_employees
                SET full_name = ?, area = ?, dni = ?,
                    active = ?, updated_at = now()
                WHERE id = ?
                """, trim(input.fullName()), trim(input.area()),
                input.dni() == null || input.dni().isBlank() ? null : input.dni().trim(),
                input.active() == null || input.active(), id);
            if (changed == 0) throw new ResponseStatusException(HttpStatus.NOT_FOUND, "Empleado no encontrado");
            // Editing the central directory must not replace existing biometric links.
            updateMethods(id, input.authenticationMethods());
            if (input.webAccount() != null) webAccounts.save(authorization, id, input.webAccount());
            Employee saved = list(authorization).stream().filter(e -> e.id() == id).findFirst().orElseThrow();
            if (!input.dni().trim().equals(saved.dni())) {
                throw new IllegalStateException("No se pudo verificar el DNI guardado");
            }
            return saved;
        } catch (DataIntegrityViolationException ex) {
            throw new ResponseStatusException(HttpStatus.CONFLICT, "El DNI ya pertenece a otro trabajador");
        }
    }

    @DeleteMapping("/{id}")
    @ResponseStatus(HttpStatus.NO_CONTENT)
    public void deactivate(@RequestHeader(value = "Authorization", required = false) String authorization,
                           @PathVariable long id) {
        requireSA(authorization);
        int changed = jdbc.update("""
            UPDATE intra.attendance_employees SET active = false, updated_at = now() WHERE id = ?
            """, id);
        if (changed == 0) throw new ResponseStatusException(HttpStatus.NOT_FOUND, "Empleado no encontrado");
    }
    /** Hidden from the live directory, but historic attendance and device events are retained. */
    @PostMapping("/{id}/archive")
    @Transactional
    public java.util.Map<String, Boolean> archive(
            @RequestHeader(value = "Authorization", required = false) String authorization,
            @PathVariable long id) {
        requireSA(authorization);
        var worker = jdbc.query("SELECT active, archived_at IS NOT NULL FROM intra.attendance_employees WHERE id = ? FOR UPDATE",
            (rs, i) -> new boolean[]{rs.getBoolean(1),rs.getBoolean(2)}, id);
        if (worker.isEmpty()) throw new ResponseStatusException(HttpStatus.NOT_FOUND, "Trabajador no encontrado");
        if (worker.get(0)[1]) throw new ResponseStatusException(HttpStatus.CONFLICT, "Trabajador ya archivado");
        if (worker.get(0)[0]) throw new ResponseStatusException(HttpStatus.CONFLICT, "Desactiva al trabajador antes de eliminarlo");
        jdbc.update("UPDATE intra.attendance_employees SET archived_at = now(),updated_at = now() WHERE id = ?", id);
        // Prevent any web account associated with this archived worker from logging in.
        jdbc.update("UPDATE intra.staff_users SET active = false WHERE employee_id = ?", id);
        jdbc.update("DELETE FROM intra.staff_sessions WHERE user_id IN (SELECT id FROM intra.staff_users WHERE employee_id = ?)",id);
        return java.util.Map.of("archived", true);
    }

}

package com.salomon.intranet.attendance;

import com.salomon.intranet.auth.StaffAuthController;
import org.springframework.format.annotation.DateTimeFormat;
import org.springframework.http.HttpStatus;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.RequestHeader;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RequestParam;
import org.springframework.web.bind.annotation.RestController;
import org.springframework.web.server.ResponseStatusException;

import java.time.LocalDate;
import java.time.ZoneId;
import java.util.List;
import java.util.Map;
import java.util.HashMap;
import java.time.LocalTime;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.transaction.annotation.Transactional;

@RestController
@RequestMapping("/api/v1/attendance")
public class AttendanceController {
    private static final ZoneId LIMA = ZoneId.of("America/Lima");
    private final JdbcTemplate jdbc;
    private final StaffAuthController auth;

    public AttendanceController(JdbcTemplate jdbc, StaffAuthController auth) {
        this.jdbc = jdbc;
        this.auth = auth;
    }

    public record DailyPunch(long id, String time, String deviceName,
                             String deviceUserId, String verifyMode) {}
    public record AttendanceRow(long userId, String employeeCode, String fullName,
                                String area, String company, String dni,
                                List<DailyPunch> punches,
                                DailyAttendanceRules.Times times, Map<String, ManualAdjustment> adjustments, Map<Integer, Observation> observations) {}
    public record UnlinkedPunch(String deviceName, String deviceUserId,
                                String time, String verifyMode) {}
    public record AttendanceKpis(int totalUsers, int withPunches,
                                 int totalPunches, int withoutPunches) {}
    public record AttendanceResponse(LocalDate date, AttendanceKpis kpis,
                                     List<AttendanceRow> users,
                                     List<UnlinkedPunch> unlinkedPunches) {}

    private record RawPunch(long id, Long employeeId, long deviceId, String deviceName,
                            String pin, String time, String verifyMode) {}

    /** Biometrics are the source of truth for daily marks; staff login accounts are not employees. */
    @GetMapping
    public AttendanceResponse list(
        @RequestHeader(value = "Authorization", required = false) String authorization,
        @RequestParam(required = false) @DateTimeFormat(iso = DateTimeFormat.ISO.DATE) LocalDate date) {
        var viewer = auth.me(authorization);
        if (!java.util.Set.of("SA","ADMIN","GERENCIA","RECEPCION","SOPORTE_TI").contains(viewer.role())) {
            throw new ResponseStatusException(HttpStatus.FORBIDDEN, "Acceso restringido");
        }
        LocalDate day = date == null ? LocalDate.now(LIMA) : date;
        if (day.isBefore(LocalDate.of(2000, 1, 1)) || day.isAfter(LocalDate.now(LIMA).plusDays(1))) {
            throw new ResponseStatusException(HttpStatus.BAD_REQUEST, "Fecha fuera del rango permitido");
        }

        var employees = jdbc.query("""
            SELECT id, full_name, employee_code, area, company, dni
            FROM intra.attendance_employees
            WHERE active = true
            ORDER BY full_name, id
            """, (rs, i) -> new AttendanceRow(
                rs.getLong("id"), rs.getString("employee_code"), rs.getString("full_name"),
                rs.getString("area"), rs.getString("company"), rs.getString("dni"),
                new java.util.ArrayList<>(), null, Map.of(), Map.of()));

        // Compare local time to local day. No shifting due to database/server time zone.
        var punches = jdbc.query("""
            SELECT p.id, link.employee_id, p.device_id, d.name AS device_name, p.device_user_id,
                   to_char(p.device_punched_at, 'HH24:MI:SS') AS local_time, p.verify_mode
            FROM intra.biometric_attendance_events p
            JOIN intra.biometric_devices d ON d.id = p.device_id
            LEFT JOIN intra.attendance_employee_device_ids link
                ON link.device_id = p.device_id AND link.device_user_id = p.device_user_id
            WHERE p.device_punched_at >= ?
              AND p.device_punched_at < ?
            ORDER BY p.device_punched_at, p.id
            """, (rs, i) -> new RawPunch(
                rs.getLong("id"), rs.getObject("employee_id", Long.class), rs.getLong("device_id"),
                rs.getString("device_name"), rs.getString("device_user_id"),
                rs.getString("local_time"), rs.getString("verify_mode")),
            day.atStartOfDay(), day.plusDays(1).atStartOfDay());

        java.util.Map<Long, AttendanceRow> byId = new java.util.HashMap<>();
        for (AttendanceRow employee : employees) byId.put(employee.userId(), employee);
        List<UnlinkedPunch> unlinked = new java.util.ArrayList<>();
        int saved = 0;
        for (RawPunch punch : punches) {
            AttendanceRow row = punch.employeeId() == null ? null : byId.get(punch.employeeId());
            if (row == null) {
                // Device-admin PINs are technical credentials, not worker attendance.
                Integer reserved = jdbc.queryForObject("""
                    SELECT count(*) FROM intra.biometric_device_admins a
                    WHERE a.device_id=? AND a.pin=?
                    """,Integer.class,punch.deviceId(),punch.pin());
                if (reserved == null || reserved == 0) unlinked.add(new UnlinkedPunch(
                    punch.deviceName(), punch.pin(), punch.time(), punch.verifyMode()));
                continue;
            }
            row.punches().add(new DailyPunch(
                punch.id(), punch.time(), punch.deviceName(), punch.pin(), punch.verifyMode()));
            saved++;
        }
        Map<Long, Map<String, ManualAdjustment>> manual = new HashMap<>();
        jdbc.query("""
            SELECT DISTINCT ON (employee_id, field) employee_id, field,
              to_char(corrected_time,'HH24:MI:SS') AS corrected_time,
              to_char(previous_time,'HH24:MI:SS') AS previous_time,
              reason, actor_username, actor_role, created_at::text AS created_at
            FROM intra.attendance_manual_adjustments WHERE work_date=?
            ORDER BY employee_id,field,id DESC
            """, rs -> {
                manual.computeIfAbsent(rs.getLong("employee_id"), k -> new HashMap<>())
                    .put(rs.getString("field"), new ManualAdjustment(rs.getString("corrected_time"),
                        rs.getString("previous_time"), rs.getString("reason"), rs.getString("actor_username"),
                        rs.getString("actor_role"), rs.getString("created_at")));
            }, day);
        Map<Long, Map<Integer, Observation>> observations = new HashMap<>();
        jdbc.query("""
            SELECT DISTINCT ON (employee_id,slot) employee_id,slot,observation,
                   actor_username,created_at::text AS created_at
            FROM intra.attendance_observations WHERE work_date=?
            ORDER BY employee_id,slot,id DESC
            """, rs -> {
                observations.computeIfAbsent(rs.getLong("employee_id"), k -> new HashMap<>())
                    .put(rs.getInt("slot"),new Observation(rs.getString("observation"),
                        rs.getString("actor_username"),rs.getString("created_at")));
            }, day);
        employees = employees.stream().map(e -> {
            var adjustments = manual.getOrDefault(e.userId(), Map.of());
            var original = DailyAttendanceRules.classify(e.punches().stream().map(DailyPunch::time).toList());
            return new AttendanceRow(e.userId(), e.employeeCode(), e.fullName(), e.area(), e.company(), e.dni(),
                e.punches(), new DailyAttendanceRules.Times(
                    effective(adjustments,"ENTRY",original.entryTime()),
                    effective(adjustments,"EXIT",original.exitTime()),
                    effective(adjustments,"LUNCH_OUT",original.lunchOutTime()),
                    effective(adjustments,"LUNCH_RETURN",original.lunchReturnTime())
                ), adjustments, observations.getOrDefault(e.userId(), Map.of()));
        }).toList();
        int withPunches = (int) employees.stream().filter(e -> !e.punches().isEmpty() || !e.adjustments().isEmpty()).count();
        return new AttendanceResponse(day,
            new AttendanceKpis(employees.size(), withPunches, saved,
                employees.size() - withPunches), employees, unlinked);
    }

    public record Observation(String text, String actor, String createdAt) {}
    public record ObservationInput(long employeeId, LocalDate date, int slot, String text) {}

    @PostMapping("/observations")
    @Transactional
    public Map<String,Object> saveObservation(
        @RequestHeader(value="Authorization",required=false) String authorization,
        @RequestBody ObservationInput input) {
        var viewer = auth.me(authorization);
        if (!java.util.Set.of("SA","ADMIN","RECEPCION").contains(viewer.role())) {
            throw new ResponseStatusException(HttpStatus.FORBIDDEN,"No autorizado a editar observaciones");
        }
        if (input == null || input.date() == null || input.text() == null ||
            input.text().length() > 1000 || (input.slot() != 1 && input.slot() != 2) ||
            input.date().isBefore(LocalDate.of(2000,1,1)) || input.date().isAfter(LocalDate.now(LIMA))) {
            throw new ResponseStatusException(HttpStatus.BAD_REQUEST,"Observación inválida");
        }
        if ("RECEPCION".equals(viewer.role()) && !input.date().equals(LocalDate.now(LIMA))) {
            throw new ResponseStatusException(HttpStatus.FORBIDDEN,"Recepción solo edita observaciones de hoy");
        }
        var locked = jdbc.query("SELECT id FROM intra.attendance_employees WHERE id=? AND active FOR UPDATE",
            (rs,i)->rs.getLong(1), input.employeeId());
        if (locked.isEmpty()) throw new ResponseStatusException(HttpStatus.NOT_FOUND,"Trabajador no encontrado");
        jdbc.update("""
            INSERT INTO intra.attendance_observations(employee_id,work_date,slot,observation,actor_id,actor_username)
            VALUES (?,?,?,?,?,?)
            """, input.employeeId(),input.date(),input.slot(),input.text().strip(),viewer.id(),viewer.username());
        return Map.of("saved",true);
    }

    public record ManualAdjustment(String time, String previousTime, String reason,
                                   String actor, String role, String createdAt) {}
    public record AdjustmentInput(long employeeId, LocalDate date, String field, String time, String reason) {}

    private static String effective(Map<String, ManualAdjustment> values, String key, String fallback) {
        return values.containsKey(key) ? values.get(key).time() : fallback;
    }

    @PostMapping("/adjustments")
    @Transactional
    public Map<String, Object> adjust(
        @RequestHeader(value="Authorization",required=false) String authorization,
        @RequestBody AdjustmentInput input) {
        var viewer = auth.me(authorization);
        if (!java.util.Set.of("SA","ADMIN","RECEPCION").contains(viewer.role())) {
            throw new ResponseStatusException(HttpStatus.FORBIDDEN,"No puedes modificar asistencia");
        }
        if (input == null || input.date() == null || input.field() == null || input.time() == null ||
            input.reason() == null || input.reason().trim().length() < 5 || input.reason().length() > 500 ||
            !java.util.Set.of("ENTRY","EXIT","LUNCH_OUT","LUNCH_RETURN").contains(input.field()) ||
            input.date().isBefore(LocalDate.of(2000,1,1)) || input.date().isAfter(LocalDate.now(LIMA))) {
            throw new ResponseStatusException(HttpStatus.BAD_REQUEST,"Datos de corrección inválidos");
        }
        boolean reception = "RECEPCION".equals(viewer.role());
        if (reception && !input.date().equals(LocalDate.now(LIMA))) {
            throw new ResponseStatusException(HttpStatus.FORBIDDEN,"Recepción solo modifica las horas del día actual");
        }
        final LocalTime time;
        try { time = LocalTime.parse(input.time()); }
        catch (java.time.DateTimeException ex) {
            throw new ResponseStatusException(HttpStatus.BAD_REQUEST,"Hora inválida");
        }
        if (input.date().equals(LocalDate.now(LIMA)) && time.isAfter(LocalTime.now(LIMA))) {
            throw new ResponseStatusException(HttpStatus.BAD_REQUEST,"No se puede registrar una hora futura");
        }
        var locked = jdbc.query("SELECT id FROM intra.attendance_employees WHERE id=? AND active FOR UPDATE",
            (rs,i)->rs.getLong(1), input.employeeId());
        if (locked.isEmpty()) throw new ResponseStatusException(HttpStatus.NOT_FOUND,"Trabajador no encontrado");
        var row = list(authorization,input.date()).users().stream()
            .filter(e -> e.userId()==input.employeeId()).findFirst().orElseThrow();
        var times = row.times();
        String previous = switch(input.field()) {
            case "ENTRY" -> times.entryTime();
            case "EXIT" -> times.exitTime();
            case "LUNCH_OUT" -> times.lunchOutTime();
            default -> times.lunchReturnTime();
        };
        String next = time.toString();
        String entry = input.field().equals("ENTRY") ? next : times.entryTime();
        String exit = input.field().equals("EXIT") ? next : times.exitTime();
        String out = input.field().equals("LUNCH_OUT") ? next : times.lunchOutTime();
        String back = input.field().equals("LUNCH_RETURN") ? next : times.lunchReturnTime();
        LocalTime prior = null;
        for (String value : new String[]{entry,out,back,exit}) {
            if (value==null) continue;
            LocalTime current = LocalTime.parse(value);
            if(prior!=null && current.isBefore(prior))
                throw new ResponseStatusException(HttpStatus.BAD_REQUEST,"Las horas deben respetar el orden de la jornada");
            prior=current;
        }
        jdbc.update("""
            INSERT INTO intra.attendance_manual_adjustments
            (employee_id,work_date,field,previous_time,corrected_time,reason,actor_id,actor_username,actor_role)
            VALUES (?,?,?,?,?,?,?,?,?)
            """,input.employeeId(),input.date(),input.field(),
            previous == null ? null : java.sql.Time.valueOf(LocalTime.parse(previous)),
            java.sql.Time.valueOf(time),input.reason().trim(),viewer.id(),viewer.username(),viewer.role());
        return Map.of("saved",true);
    }

    public record BiometricPunch(long id, String serialNumber, String deviceUserId,
                                 String employeeName, String employeeCode, String localTime,
                                 String status, String verifyMode, String workCode) {}

    /** Read-only operational view of saved raw punches, including unlinked PINs. */
    @GetMapping("/punches")
    public List<BiometricPunch> punches(
        @RequestHeader(value = "Authorization", required = false) String authorization,
        @RequestParam(defaultValue = "50") int limit) {
        var viewer = auth.me(authorization);
        if (!"SA".equals(viewer.role())) {
            throw new ResponseStatusException(HttpStatus.FORBIDDEN, "Acceso restringido");
        }
        if (limit < 1 || limit > 200) {
            throw new ResponseStatusException(HttpStatus.BAD_REQUEST, "El límite debe estar entre 1 y 200");
        }
        return jdbc.query("""
            SELECT p.id, d.serial_number, p.device_user_id, e.full_name, e.employee_code,
                   to_char(p.device_punched_at, 'YYYY-MM-DD HH24:MI:SS') AS local_time,
                   p.attendance_status, p.verify_mode, p.work_code
            FROM intra.biometric_attendance_events p
            JOIN intra.biometric_devices d ON d.id = p.device_id
            LEFT JOIN intra.attendance_employee_device_ids link
              ON link.device_id = p.device_id AND link.device_user_id = p.device_user_id
            LEFT JOIN intra.attendance_employees e ON e.id = link.employee_id
            ORDER BY p.punched_at DESC, p.id DESC
            LIMIT ?
            """, (rs, i) -> new BiometricPunch(
                rs.getLong("id"), rs.getString("serial_number"), rs.getString("device_user_id"),
                rs.getString("full_name"), rs.getString("employee_code"), rs.getString("local_time"),
                rs.getString("attendance_status"), rs.getString("verify_mode"), rs.getString("work_code")),
            limit);
    }

}

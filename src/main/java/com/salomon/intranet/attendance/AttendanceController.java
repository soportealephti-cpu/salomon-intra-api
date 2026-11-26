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
                                DailyAttendanceRules.Times times, Map<String, ManualAdjustment> adjustments, Map<Integer, Observation> observations, List<ManualAdjustment> adjustmentHistory, Boolean late, List<TemporaryOuting> outings) {}
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
                new java.util.ArrayList<>(), null, Map.of(), Map.of(), List.of(), null, List.of()));

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
        Map<Long,List<ManualAdjustment>> history=new HashMap<>();
        Map<Long,Map<String,String>> legacySources=new HashMap<>();
        jdbc.query("""
            SELECT employee_id,field,action,to_char(source_time,'HH24:MI:SS') AS source_time,
              to_char(corrected_time,'HH24:MI:SS') AS corrected_time,
              to_char(previous_time,'HH24:MI:SS') AS previous_time,
              reason, actor_username, actor_role, created_at::text AS created_at
            FROM intra.attendance_manual_adjustments WHERE work_date=?
            ORDER BY id
            """, rs -> {
                long employee=rs.getLong("employee_id");
                String field=rs.getString("field"),action=rs.getString("action"),source=rs.getString("source_time");
                if("LEGACY".equals(action)){
                    var first=legacySources.computeIfAbsent(employee,k->new HashMap<>());
                    if(!first.containsKey(field))first.put(field,rs.getString("previous_time"));
                    source=first.get(field);
                }
                var adjustment=new ManualAdjustment(rs.getString("corrected_time"),rs.getString("previous_time"),
                    rs.getString("reason"),rs.getString("actor_username"),rs.getString("actor_role"),
                    rs.getString("created_at"),field,action,source);
                manual.computeIfAbsent(employee,k->new HashMap<>()).put(field,adjustment);
                history.computeIfAbsent(employee,k->new java.util.ArrayList<>()).add(adjustment);
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
        // Use only effective schedules for this date. No invented default start time.
        Map<Long,LocalTime> scheduledStarts=new HashMap<>();
        jdbc.query("""
            SELECT employee_id,starts_at,working FROM (
                SELECT DISTINCT ON (employee_id) employee_id,starts_at,working
                FROM intra.payroll_schedules
                WHERE effective_from<=? AND weekday=?
                ORDER BY employee_id,effective_from DESC
            ) effective_schedule
            """,rs->{
                if(rs.getBoolean("working"))scheduledStarts.put(rs.getLong("employee_id"),rs.getObject("starts_at",LocalTime.class));
            },day,day.getDayOfWeek().getValue());
        Map<Long,List<TemporaryOuting>> outings=new HashMap<>();
        jdbc.query("""
            SELECT employee_id,id,to_char(departure,'HH24:MI:SS') AS departure,
                   to_char(return_time,'HH24:MI:SS') AS return_time,observation,
                   actor_username,created_at::text AS created_at,
                   return_actor_username,returned_at::text AS returned_at,return_observation
            FROM intra.attendance_outings WHERE work_date=? ORDER BY departure,id
            """,rs->{
                outings.computeIfAbsent(rs.getLong("employee_id"),k->new java.util.ArrayList<>())
                    .add(new TemporaryOuting(rs.getLong("id"),rs.getString("departure"),rs.getString("return_time"),
                        rs.getString("observation"),rs.getString("actor_username"),rs.getString("created_at"),
                        rs.getString("return_actor_username"),rs.getString("returned_at"),rs.getString("return_observation")));
            },day);
        employees = employees.stream().map(e -> {
            var adjustments = manual.getOrDefault(e.userId(), Map.of());
            var changes=history.getOrDefault(e.userId(),List.of());
            var resolved=resolve(day,e.punches(),adjustments,changes);
            var scheduled=scheduledStarts.get(e.userId());
            Boolean late=scheduled==null||resolved.entryTime()==null?null:LocalTime.parse(resolved.entryTime()).isAfter(scheduled);
            return new AttendanceRow(e.userId(), e.employeeCode(), e.fullName(), e.area(), e.company(), e.dni(),
                e.punches(),resolved,adjustments,observations.getOrDefault(e.userId(),Map.of()),changes,late,outings.getOrDefault(e.userId(),List.of()));
        }).toList();
        int withPunches = (int) employees.stream().filter(e -> !e.punches().isEmpty() || e.times().entryTime()!=null || e.times().exitTime()!=null || e.times().lunchOutTime()!=null || e.times().lunchReturnTime()!=null).count();
        return new AttendanceResponse(day,
            new AttendanceKpis(employees.size(), withPunches, saved,
                employees.size() - withPunches), employees, unlinked);
    }

    public record TemporaryOuting(long id,String departure,String returnTime,String observation,
                                  String actor,String createdAt,String returnActor,String returnedAt,String returnObservation) {}
    public record OutingInput(long employeeId,LocalDate date,Long outingId,String departure,
                              String returnTime,String observation,String returnObservation) {}

    /** Independent of the four biometric slots. A departure can be closed exactly once. */
    @PostMapping("/outings")
    @Transactional
    public Map<String,Object> saveOuting(
        @RequestHeader(value="Authorization",required=false) String authorization,
        @RequestBody OutingInput input) {
        var viewer=auth.me(authorization);
        if(!java.util.Set.of("SA","ADMIN","RECEPCION").contains(viewer.role()))
            throw new ResponseStatusException(HttpStatus.FORBIDDEN,"No autorizado a registrar salidas");
        if(input==null||input.date()==null||input.employeeId()<1||
            input.date().isBefore(LocalDate.of(2000,1,1))||input.date().isAfter(LocalDate.now(LIMA)))
            throw new ResponseStatusException(HttpStatus.BAD_REQUEST,"Fecha o trabajador inválido");
        if("RECEPCION".equals(viewer.role())&&!input.date().equals(LocalDate.now(LIMA)))
            throw new ResponseStatusException(HttpStatus.FORBIDDEN,"Recepción solo registra salidas de hoy");
        if(input.returnObservation()!=null&&input.returnObservation().length()>1000)
            throw new ResponseStatusException(HttpStatus.BAD_REQUEST,"Observación demasiado extensa");
        var locked=jdbc.query("SELECT id FROM intra.attendance_employees WHERE id=? AND active AND archived_at IS NULL FOR UPDATE",
            (rs,i)->rs.getLong(1),input.employeeId());
        if(locked.isEmpty())throw new ResponseStatusException(HttpStatus.NOT_FOUND,"Trabajador no encontrado");
        LocalTime back=parseOutingTime(input.returnTime(),input.date());
        String returnNote=input.returnObservation()==null?"":input.returnObservation().strip();
        if(input.outingId()!=null){
            if(input.outingId()<1||back==null)
                throw new ResponseStatusException(HttpStatus.BAD_REQUEST,"Indica la hora de regreso");
            var rows=jdbc.query("""
                SELECT departure FROM intra.attendance_outings
                WHERE id=? AND employee_id=? AND work_date=? AND return_time IS NULL
                """,(rs,i)->rs.getObject(1,LocalTime.class),input.outingId(),input.employeeId(),input.date());
            if(rows.isEmpty())throw new ResponseStatusException(HttpStatus.CONFLICT,"Salida no disponible o ya cerrada");
            if(!back.isAfter(rows.getFirst()))throw new ResponseStatusException(HttpStatus.BAD_REQUEST,"El regreso debe ser posterior a la salida");
            if(jdbc.update("""
                UPDATE intra.attendance_outings SET return_time=?,return_actor_id=?,return_actor_username=?,
                    returned_at=now(),return_observation=?
                WHERE id=? AND employee_id=? AND work_date=? AND return_time IS NULL
                """,java.sql.Time.valueOf(back),viewer.id(),viewer.username(),returnNote,input.outingId(),input.employeeId(),input.date())!=1)
                throw new ResponseStatusException(HttpStatus.CONFLICT,"La salida ya fue cerrada");
            return Map.of("saved",true);
        }
        LocalTime departure=parseOutingTime(input.departure(),input.date());
        if(departure==null||input.observation()==null||input.observation().strip().isEmpty()||input.observation().length()>1000||
            (back!=null&&!back.isAfter(departure))||(back==null&&!returnNote.isEmpty()))
            throw new ResponseStatusException(HttpStatus.BAD_REQUEST,"Indica salida, motivo y un regreso posterior, si corresponde");
        var overlap=jdbc.queryForObject("""
            SELECT EXISTS(SELECT 1 FROM intra.attendance_outings
                WHERE employee_id=? AND work_date=?
                  AND departure < COALESCE(CAST(? AS time),time '24:00')
                  AND COALESCE(return_time,time '24:00') > CAST(? AS time))
            """,Boolean.class,input.employeeId(),input.date(),back==null?null:java.sql.Time.valueOf(back),java.sql.Time.valueOf(departure));
        if(Boolean.TRUE.equals(overlap))throw new ResponseStatusException(HttpStatus.CONFLICT,"Ya existe una salida que coincide con ese horario");
        if(back==null){
            jdbc.update("""
                INSERT INTO intra.attendance_outings(employee_id,work_date,departure,observation,actor_id,actor_username)
                VALUES (?,?,?,?,?,?)
                """,input.employeeId(),input.date(),java.sql.Time.valueOf(departure),input.observation().strip(),viewer.id(),viewer.username());
        }else{
            jdbc.update("""
                INSERT INTO intra.attendance_outings(employee_id,work_date,departure,observation,actor_id,actor_username,
                    return_time,return_actor_id,return_actor_username,returned_at,return_observation)
                VALUES (?,?,?,?,?,?,?,?,?,now(),?)
                """,input.employeeId(),input.date(),java.sql.Time.valueOf(departure),input.observation().strip(),viewer.id(),viewer.username(),
                java.sql.Time.valueOf(back),viewer.id(),viewer.username(),returnNote);
        }
        return Map.of("saved",true);
    }
    private static LocalTime parseOutingTime(String value,LocalDate day){
        if(value==null||value.isBlank())return null;
        LocalTime time;
        try{time=LocalTime.parse(value).withNano(0);}
        catch(java.time.DateTimeException ex){throw new ResponseStatusException(HttpStatus.BAD_REQUEST,"Hora inválida");}
        if(day.equals(LocalDate.now(LIMA))&&time.isAfter(LocalTime.now(LIMA)))
            throw new ResponseStatusException(HttpStatus.BAD_REQUEST,"No se puede registrar una hora futura");
        return time;
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
            input.text().length() > 1000 || (input.slot() != 1 && input.slot() != 2 && input.slot() != 3) ||
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
                                   String actor, String role, String createdAt,String field,String action,String sourceTime) {}
    public record AdjustmentInput(long employeeId, LocalDate date, String field, String time, String reason,String action) {}

    private static DailyAttendanceRules.Times resolve(LocalDate day,List<DailyPunch> punches,Map<String,ManualAdjustment> adjustments,List<ManualAdjustment> history){
        var raw=punches.stream().map(DailyPunch::time).toList();
        if(day.isBefore(DailyAttendanceRules.SEQUENCE_FROM)){
            var original=DailyAttendanceRules.legacyClassify(raw);
            return new DailyAttendanceRules.Times(effective(adjustments,"ENTRY",original.entryTime()),effective(adjustments,"EXIT",original.exitTime()),
                effective(adjustments,"LUNCH_OUT",original.lunchOutTime()),effective(adjustments,"LUNCH_RETURN",original.lunchReturnTime()));
        }
        Map<String,DailyAttendanceRules.Slot> slots=new HashMap<>();
        adjustments.forEach((k,a)->slots.put(k,new DailyAttendanceRules.Slot(a.time(),a.sourceTime())));
        var excluded=history.stream().flatMap(a->{
            if("MOVE_OUT".equals(a.action()))return java.util.stream.Stream.of(a.sourceTime(),a.previousTime());
            if("CLEAR".equals(a.action()))return java.util.stream.Stream.of(a.sourceTime());
            return java.util.stream.Stream.<String>empty();
        }).filter(java.util.Objects::nonNull).toList();
        return DailyAttendanceRules.sequence(raw,slots,excluded);
    }
    private static void validateOrder(DailyAttendanceRules.Times times){
        LocalTime prior=null;
        for(String value:new String[]{times.entryTime(),times.lunchOutTime(),times.lunchReturnTime(),times.exitTime()}){
            if(value==null)continue;
            LocalTime current=LocalTime.parse(value);
            if(prior!=null&&current.isBefore(prior))throw new ResponseStatusException(HttpStatus.BAD_REQUEST,"Las horas deben respetar el orden de la jornada");
            prior=current;
        }
    }

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
        String action=input==null||input.action()==null?"SET":input.action();
        if (input == null || input.date() == null || input.field() == null ||
            !java.util.Set.of("SET","FILL","CLEAR").contains(action) ||
            (!"CLEAR".equals(action)&&input.time()==null) || ("CLEAR".equals(action)&&"ENTRY".equals(input.field())) ||
            ("FILL".equals(action)&&input.date().isBefore(DailyAttendanceRules.SEQUENCE_FROM)) ||
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
        try { time = "CLEAR".equals(action)?null:LocalTime.parse(input.time()); }
        catch (java.time.DateTimeException ex) {
            throw new ResponseStatusException(HttpStatus.BAD_REQUEST,"Hora inválida");
        }
        if (time!=null && input.date().equals(LocalDate.now(LIMA)) && time.isAfter(LocalTime.now(LIMA))) {
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
        var current=row.adjustments().get(input.field());
        String source=current==null||"CLEAR".equals(current.action())||"MOVE_OUT".equals(current.action())?previous:current.sourceTime();
        if("FILL".equals(action)){
            if(current!=null&&current.time()!=null)throw new ResponseStatusException(HttpStatus.BAD_REQUEST,"Ya existe un ajuste manual; corrige esa hora");
            source=null;
        } else if("CLEAR".equals(action)&&previous==null) {
            throw new ResponseStatusException(HttpStatus.BAD_REQUEST,"No hay una hora que restablecer");
        }
        String next=time==null?null:time.format(java.time.format.DateTimeFormatter.ofPattern("HH:mm:ss"));
        Map<String,ManualAdjustment> projected=new HashMap<>(row.adjustments());
        var change=new ManualAdjustment(next,previous,input.reason().trim(),viewer.username(),viewer.role(),"",input.field(),action,source);
        projected.put(input.field(),change);
        var changes=new java.util.ArrayList<>(row.adjustmentHistory());changes.add(change);
        validateOrder(resolve(input.date(),row.punches(),projected,changes));
        jdbc.update("""
            INSERT INTO intra.attendance_manual_adjustments
            (employee_id,work_date,field,previous_time,corrected_time,reason,actor_id,actor_username,actor_role,action,source_time)
            VALUES (?,?,?,?,?,?,?,?,?,?,?)
            """,input.employeeId(),input.date(),input.field(),
            previous==null?null:java.sql.Time.valueOf(LocalTime.parse(previous)),
            time==null?null:java.sql.Time.valueOf(time),input.reason().trim(),viewer.id(),viewer.username(),viewer.role(),action,
            source==null?null:java.sql.Time.valueOf(LocalTime.parse(source)));
        return Map.of("saved",true);
    }

    public record ReorderInput(long employeeId,LocalDate date,String fromField,String toField,String expectedTime,String reason) {}

    private static String slotTime(DailyAttendanceRules.Times times,String field){
        return switch(field){
            case "ENTRY" -> times.entryTime();
            case "LUNCH_OUT" -> times.lunchOutTime();
            case "LUNCH_RETURN" -> times.lunchReturnTime();
            case "EXIT" -> times.exitTime();
            default -> throw new ResponseStatusException(HttpStatus.BAD_REQUEST,"Campo inválido");
        };
    }
    private static String slotLabel(String field){
        return switch(field){
            case "ENTRY" -> "Entrada";
            case "LUNCH_OUT" -> "Salida a almuerzo";
            case "LUNCH_RETURN" -> "Regreso de almuerzo";
            case "EXIT" -> "Salida del trabajo";
            default -> field;
        };
    }
    private static java.sql.Time sqlTime(String value){
        return value==null?null:java.sql.Time.valueOf(LocalTime.parse(value));
    }
    /** Move a displayed time without deleting biometric records or overwriting another slot. */
    @PostMapping("/reorder")
    @Transactional
    public Map<String,Object> reorder(
        @RequestHeader(value="Authorization",required=false) String authorization,
        @RequestBody ReorderInput input){
        var viewer=auth.me(authorization);
        if(!java.util.Set.of("SA","ADMIN","RECEPCION").contains(viewer.role()))
            throw new ResponseStatusException(HttpStatus.FORBIDDEN,"No puedes modificar asistencia");
        if(input==null||input.date()==null||input.employeeId()<1||input.fromField()==null||input.toField()==null||
            !DailyAttendanceRules.ORDER.contains(input.fromField())||!DailyAttendanceRules.ORDER.contains(input.toField())||
            input.fromField().equals(input.toField())||input.expectedTime()==null||input.reason()==null||
            input.reason().strip().length()<5||input.reason().length()>350||
            input.date().isBefore(LocalDate.of(2000,1,1))||input.date().isAfter(LocalDate.now(LIMA)))
            throw new ResponseStatusException(HttpStatus.BAD_REQUEST,"Datos de reordenamiento inválidos");
        if("RECEPCION".equals(viewer.role())&&!input.date().equals(LocalDate.now(LIMA)))
            throw new ResponseStatusException(HttpStatus.FORBIDDEN,"Recepción solo modifica el día actual");
        LocalTime expected;
        try{expected=LocalTime.parse(input.expectedTime());}
        catch(java.time.DateTimeException ex){throw new ResponseStatusException(HttpStatus.BAD_REQUEST,"Hora inválida");}
        var locked=jdbc.query("SELECT id FROM intra.attendance_employees WHERE id=? AND active AND archived_at IS NULL FOR UPDATE",
            (rs,i)->rs.getLong(1),input.employeeId());
        if(locked.isEmpty())throw new ResponseStatusException(HttpStatus.NOT_FOUND,"Trabajador no encontrado");
        var row=list(authorization,input.date()).users().stream().filter(e->e.userId()==input.employeeId()).findFirst().orElseThrow();
        var times=row.times();
        String value=slotTime(times,input.fromField());
        if(value==null||!LocalTime.parse(value).equals(expected)||slotTime(times,input.toField())!=null)
            throw new ResponseStatusException(HttpStatus.CONFLICT,"La hora cambió o el destino ya tiene una hora. Actualiza el listado");
        var current=row.adjustments().get(input.fromField());
        String source=current==null||current.time()==null||current.sourceTime()==null?value:current.sourceTime();
        String reason="Reordenado: "+slotLabel(input.fromField())+" → "+slotLabel(input.toField())+". "+input.reason().strip();
        var out=new ManualAdjustment(null,value,reason,viewer.username(),viewer.role(),"",input.fromField(),"MOVE_OUT",source);
        var in=new ManualAdjustment(value,null,reason,viewer.username(),viewer.role(),"",input.toField(),"MOVE_IN",null);
        var projected=new HashMap<>(row.adjustments());
        projected.put(input.fromField(),out);projected.put(input.toField(),in);
        var history=new java.util.ArrayList<>(row.adjustmentHistory());history.add(out);history.add(in);
        var result=resolve(input.date(),row.punches(),projected,history);
        validateOrder(result);
        for(String field:DailyAttendanceRules.ORDER){
            String desired=field.equals(input.fromField())?null:field.equals(input.toField())?value:slotTime(times,field);
            if(!java.util.Objects.equals(slotTime(result,field),desired))
                throw new ResponseStatusException(HttpStatus.CONFLICT,"El movimiento cambiaría otras horas. Revisa las marcaciones y elige otro destino");
        }
        for(var change:java.util.List.of(out,in)){
            jdbc.update("""
                INSERT INTO intra.attendance_manual_adjustments
                (employee_id,work_date,field,previous_time,corrected_time,reason,actor_id,actor_username,actor_role,action,source_time)
                VALUES (?,?,?,?,?,?,?,?,?,?,?)
                """,input.employeeId(),input.date(),change.field(),sqlTime(change.previousTime()),sqlTime(change.time()),
                change.reason(),viewer.id(),viewer.username(),viewer.role(),change.action(),sqlTime(change.sourceTime()));
        }
        return Map.of("saved",true,"times",result);
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

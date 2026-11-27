package com.salomon.intranet.attendance;

import com.salomon.intranet.auth.StaffAuthController;
import org.springframework.web.bind.annotation.*;
import org.springframework.format.annotation.DateTimeFormat;
import org.springframework.http.HttpStatus;
import org.springframework.web.server.ResponseStatusException;
import java.time.*;
import java.util.*;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.transaction.annotation.Transactional;

@RestController
@RequestMapping("/api/v1/payroll")
public class PayrollController {
    private final StaffAuthController auth;
    private final AttendanceController attendance;
    private final JdbcTemplate jdbc;
    public PayrollController(StaffAuthController auth, AttendanceController attendance, JdbcTemplate jdbc) {
        this.auth=auth; this.attendance=attendance; this.jdbc=jdbc;
    }
    public record Period(LocalDate start, LocalDate end, LocalDate through,
                         List<AttendanceController.AttendanceResponse> days, List<Schedule> schedules, List<Incident> incidents, List<OvertimeDecision> overtimeDecisions) {}
    @GetMapping
    public Period list(@RequestHeader(value="Authorization",required=false) String token,
        @RequestParam @DateTimeFormat(iso=DateTimeFormat.ISO.DATE) LocalDate start,
        @RequestParam @DateTimeFormat(iso=DateTimeFormat.ISO.DATE) LocalDate end) {
        if (!"ADMIN".equals(auth.me(token).role()))
            throw new ResponseStatusException(HttpStatus.FORBIDDEN,"Solo ADMIN");
        if(start.isBefore(LocalDate.of(2000,1,1)) || end.isBefore(start) || end.isAfter(start.plusDays(61)))
            throw new ResponseStatusException(HttpStatus.BAD_REQUEST,"Periodo inválido (máximo 62 días)");
        LocalDate today=LocalDate.now(ZoneId.of("America/Lima"));
        LocalDate through=end.isBefore(today)?end:today;
        List<AttendanceController.AttendanceResponse> days=new ArrayList<>();
        for(LocalDate day=start;!day.isAfter(through);day=day.plusDays(1)) {
            days.add(attendance.list(token,day));
        }
        var schedules=jdbc.query("""
            SELECT employee_id,effective_from,weekday,starts_at,ends_at,working,alert_from
            FROM intra.payroll_schedules WHERE effective_from<=?
            UNION ALL
            SELECT 0,effective_from,weekday,starts_at,ends_at,working,alert_from
            FROM intra.payroll_default_schedules WHERE effective_from<=?
            ORDER BY effective_from DESC
            """,(rs,i)->new Schedule(rs.getLong(1),rs.getObject(2,LocalDate.class),rs.getInt(3),rs.getObject(4,LocalTime.class).toString(),rs.getObject(5,LocalTime.class).toString(),rs.getBoolean(6),rs.getObject(7,LocalTime.class)==null?null:rs.getObject(7,LocalTime.class).toString()),end,end);
        var incidents=jdbc.query("""
            SELECT DISTINCT ON(employee_id,work_date) employee_id,work_date,kind,note
            FROM intra.payroll_incidents WHERE work_date BETWEEN ? AND ?
            ORDER BY employee_id,work_date,id DESC
            """,(rs,i)->new Incident(rs.getLong(1),rs.getObject(2,LocalDate.class),rs.getString(3),rs.getString(4)),start,end);
        var decisions=jdbc.query("""
            SELECT id,employee_id,work_date,decision,entry_time,exit_time,scheduled_start,scheduled_end,
                   observed_seconds,reason,actor_username,created_at::text
            FROM intra.payroll_overtime_decisions WHERE work_date BETWEEN ? AND ? ORDER BY id DESC
            """,(rs,i)->new OvertimeDecision(rs.getLong(1),rs.getLong(2),rs.getObject(3,LocalDate.class),
                rs.getString(4),rs.getObject(5,LocalTime.class).toString(),rs.getObject(6,LocalTime.class).toString(),
                rs.getObject(7,LocalTime.class).toString(),rs.getObject(8,LocalTime.class).toString(),
                rs.getInt(9),rs.getString(10),rs.getString(11),rs.getString(12)),start,end);
        return new Period(start,end,through,days,schedules,incidents,decisions);
    }
    public record Schedule(long employeeId, LocalDate effectiveFrom,int weekday,String startsAt,String endsAt,boolean working,String alertFrom) {}
    public record Incident(long employeeId,LocalDate date,String kind,String note) {}
    public record ScheduleInput(long employeeId,LocalDate effectiveFrom,List<Schedule> days) {}
    private long admin(String token) {
        var u=auth.me(token);
        if(!"ADMIN".equals(u.role()))throw new ResponseStatusException(HttpStatus.FORBIDDEN);
        return u.id();
    }
    private void employee(long id) {
        if(jdbc.queryForObject("SELECT count(*) FROM intra.attendance_employees WHERE id=? AND active",Integer.class,id)==0)
            throw new ResponseStatusException(HttpStatus.NOT_FOUND);
    }
    @PostMapping("/schedules")
    @Transactional
    public Map<String,Boolean> schedule(@RequestHeader(value="Authorization",required=false) String token,@RequestBody ScheduleInput in) {
        long actor=admin(token);
        if(in==null||in.effectiveFrom()==null||in.effectiveFrom().isBefore(LocalDate.of(2000,1,1))||in.days()==null||in.days().size()!=7)
            throw new ResponseStatusException(HttpStatus.BAD_REQUEST);
        if(in.employeeId()<0)throw new ResponseStatusException(HttpStatus.BAD_REQUEST);
        if(in.employeeId()==0 && in.effectiveFrom().isBefore(LocalDate.of(2026,11,26)))
            throw new ResponseStatusException(HttpStatus.BAD_REQUEST,"El horario general no modifica periodos anteriores al 26/11/2026");
        if(in.employeeId()!=0)employee(in.employeeId());
        Set<Integer> weekdays=new HashSet<>();
        for(var d:in.days()){
            if(d==null||d.weekday()<1||d.weekday()>7||!weekdays.add(d.weekday()))throw new ResponseStatusException(HttpStatus.BAD_REQUEST);
            LocalTime start,end,alert;
            try{start=LocalTime.parse(d.startsAt());end=LocalTime.parse(d.endsAt());
                alert=d.alertFrom()==null||d.alertFrom().isBlank()?start.plusMinutes(1):LocalTime.parse(d.alertFrom());}
            catch(RuntimeException ex){throw new ResponseStatusException(HttpStatus.BAD_REQUEST);}
            if(!end.isAfter(start)||alert.isBefore(start)||!alert.isBefore(end))throw new ResponseStatusException(HttpStatus.BAD_REQUEST,"La alerta debe estar entre el inicio y el fin de jornada");
            if(in.employeeId()==0){
                jdbc.update("""
                    INSERT INTO intra.payroll_default_schedules(effective_from,weekday,starts_at,ends_at,working,alert_from,actor_id)
                    VALUES (?,?,?,?,?,?,?) ON CONFLICT(effective_from,weekday)
                    DO UPDATE SET starts_at=excluded.starts_at,ends_at=excluded.ends_at,working=excluded.working,
                        alert_from=excluded.alert_from,actor_id=excluded.actor_id,updated_at=now()
                    """,in.effectiveFrom(),d.weekday(),java.sql.Time.valueOf(start),java.sql.Time.valueOf(end),d.working(),java.sql.Time.valueOf(alert),actor);
            }else{
                jdbc.update("""
                    INSERT INTO intra.payroll_schedules(employee_id,effective_from,weekday,starts_at,ends_at,working,actor_id,alert_from)
                    VALUES (?,?,?,?,?,?,?,?) ON CONFLICT(employee_id,effective_from,weekday)
                    DO UPDATE SET starts_at=excluded.starts_at,ends_at=excluded.ends_at,working=excluded.working,
                        alert_from=excluded.alert_from,actor_id=excluded.actor_id,updated_at=now()
                    """,in.employeeId(),in.effectiveFrom(),d.weekday(),java.sql.Time.valueOf(start),java.sql.Time.valueOf(end),d.working(),actor,java.sql.Time.valueOf(alert));
            }
        }
        return Map.of("saved",true);
    }
    @PostMapping("/incidents")
    @Transactional
    public Map<String,Boolean> incident(@RequestHeader(value="Authorization",required=false) String token,@RequestBody Incident in) {
        long actor=admin(token);
        if(in==null||in.date()==null||in.date().isBefore(LocalDate.of(2000,1,1))||in.kind()==null||
           !Set.of("NONE","FALTA","DM","VACACIONES","FERIADO","LCGH","LSGH","PERMISO").contains(in.kind())||
           in.note()==null||in.note().length()>500)
            throw new ResponseStatusException(HttpStatus.BAD_REQUEST);
        employee(in.employeeId());
        jdbc.update("INSERT INTO intra.payroll_incidents(employee_id,work_date,kind,note,actor_id) VALUES (?,?,?,?,?)",
            in.employeeId(),in.date(),in.kind(),in.note().strip(),actor);
        return Map.of("saved",true);
    }

    public record OvertimeDecision(long id,long employeeId,LocalDate date,String decision,String entryTime,String exitTime,
        String scheduledStart,String scheduledEnd,int observedSeconds,String reason,String actor,String createdAt) {}
    public record OvertimeInput(long employeeId,LocalDate date,String decision,String reason,long expectedDecisionId,
        String entryTime,String exitTime,String scheduledStart,String scheduledEnd) {}
    private record OvertimeSchedule(LocalTime start,LocalTime end,boolean working) {}

    @PostMapping("/overtime")
    @Transactional(isolation=org.springframework.transaction.annotation.Isolation.SERIALIZABLE)
    public Map<String,Object> overtime(@RequestHeader(value="Authorization",required=false) String token,@RequestBody OvertimeInput in){
        var actor=auth.me(token);
        if(!"ADMIN".equals(actor.role()))throw new ResponseStatusException(HttpStatus.FORBIDDEN,"Solo Administración");
        if(in==null||in.employeeId()<1||in.date()==null||in.date().isBefore(LocalDate.of(2000,1,1))||
           in.date().isAfter(LocalDate.now(ZoneId.of("America/Lima")))||in.decision()==null||
           !Set.of("APPROVED","REJECTED").contains(in.decision())||in.reason()==null||in.reason().strip().length()<3||
           in.reason().length()>500||in.expectedDecisionId()<0)
            throw new ResponseStatusException(HttpStatus.BAD_REQUEST,"Datos de revisión inválidos");
        var locked=jdbc.query("SELECT id FROM intra.attendance_employees WHERE id=? FOR UPDATE",(rs,i)->rs.getLong(1),in.employeeId());
        if(locked.isEmpty())throw new ResponseStatusException(HttpStatus.NOT_FOUND,"Trabajador no encontrado");
        var row=attendance.list(token,in.date()).users().stream().filter(e->e.userId()==in.employeeId()).findFirst()
            .orElseThrow(()->new ResponseStatusException(HttpStatus.NOT_FOUND,"Sin jornada disponible"));
        var schedules=jdbc.query("""
            SELECT starts_at,ends_at,working FROM (
              SELECT starts_at,ends_at,working,effective_from,1 AS priority
                FROM intra.payroll_schedules WHERE employee_id=? AND weekday=? AND effective_from<=?
              UNION ALL
              SELECT starts_at,ends_at,working,effective_from,0 AS priority
                FROM intra.payroll_default_schedules WHERE weekday=? AND effective_from<=?
            ) s ORDER BY priority DESC,effective_from DESC LIMIT 1
            """,(rs,i)->new OvertimeSchedule(rs.getObject(1,LocalTime.class),rs.getObject(2,LocalTime.class),rs.getBoolean(3)),
            in.employeeId(),in.date().getDayOfWeek().getValue(),in.date(),in.date().getDayOfWeek().getValue(),in.date());
        String incident=jdbc.query("""
            SELECT kind FROM intra.payroll_incidents WHERE employee_id=? AND work_date=? ORDER BY id DESC LIMIT 1
            """,(rs,i)->rs.getString(1),in.employeeId(),in.date()).stream().findFirst().orElse("NONE");
        if(schedules.isEmpty()||!schedules.getFirst().working()||!"NONE".equals(incident)||
           row.times().entryTime()==null||row.times().exitTime()==null)
            throw new ResponseStatusException(HttpStatus.CONFLICT,"No hay sobretiempo evaluable");
        var schedule=schedules.getFirst();
        var entry=LocalTime.parse(row.times().entryTime());
        var exit=LocalTime.parse(row.times().exitTime());
        int seconds=exit.toSecondOfDay()-schedule.end().toSecondOfDay();
        if(seconds<=0||!exit.isAfter(entry))
            throw new ResponseStatusException(HttpStatus.CONFLICT,"No hay sobretiempo evaluable");
        try{
            if(!entry.equals(LocalTime.parse(in.entryTime()))||!exit.equals(LocalTime.parse(in.exitTime()))||
                !schedule.start().equals(LocalTime.parse(in.scheduledStart()))||!schedule.end().equals(LocalTime.parse(in.scheduledEnd())))
                throw new ResponseStatusException(HttpStatus.CONFLICT,"La jornada o el horario cambiaron; actualiza el detalle");
        }catch(java.time.DateTimeException|NullPointerException ex){throw new ResponseStatusException(HttpStatus.BAD_REQUEST,"Horas inválidas");}
        Long latest=jdbc.queryForObject("SELECT COALESCE(max(id),0) FROM intra.payroll_overtime_decisions WHERE employee_id=? AND work_date=?",Long.class,in.employeeId(),in.date());
        if(latest==null||latest!=in.expectedDecisionId())throw new ResponseStatusException(HttpStatus.CONFLICT,"Ya existe otra revisión; actualiza");
        jdbc.update("""
            INSERT INTO intra.payroll_overtime_decisions(employee_id,work_date,decision,entry_time,exit_time,
                scheduled_start,scheduled_end,observed_seconds,reason,actor_id,actor_username)
            VALUES (?,?,?,?,?,?,?,?,?,?,?)
            """,in.employeeId(),in.date(),in.decision(),java.sql.Time.valueOf(entry),java.sql.Time.valueOf(exit),
            java.sql.Time.valueOf(schedule.start()),java.sql.Time.valueOf(schedule.end()),seconds,in.reason().strip(),actor.id(),actor.username());
        return Map.of("saved",true);
    }
}

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
                         List<AttendanceController.AttendanceResponse> days, List<Schedule> schedules, List<Incident> incidents) {}
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
        return new Period(start,end,through,days,schedules,incidents);
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
                alert=d.alertFrom()==null||d.alertFrom().isBlank()?start.plusMinutes(15):LocalTime.parse(d.alertFrom());}
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
}

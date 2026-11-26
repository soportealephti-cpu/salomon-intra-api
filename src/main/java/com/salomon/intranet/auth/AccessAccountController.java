package com.salomon.intranet.auth;

import java.nio.charset.StandardCharsets;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.Set;
import org.springframework.dao.DataIntegrityViolationException;
import org.springframework.http.HttpStatus;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.security.crypto.bcrypt.BCryptPasswordEncoder;
import org.springframework.transaction.annotation.Transactional;
import org.springframework.web.bind.annotation.*;
import org.springframework.web.server.ResponseStatusException;

@RestController
@RequestMapping("/api/v1/access-accounts")
public class AccessAccountController {
    private static final Set<String> ROLES=Set.of("SA","ADMIN","GERENCIA","RECEPCION","SOPORTE_TI");
    private final JdbcTemplate jdbc;
    private final StaffAuthController auth;
    private final BCryptPasswordEncoder encoder=new BCryptPasswordEncoder(12);
    public AccessAccountController(JdbcTemplate jdbc,StaffAuthController auth){this.jdbc=jdbc;this.auth=auth;}
    public record Account(long id,String username,String displayName,String role,boolean active,Long employeeId,String employeeName){}
    public record Input(String username,String displayName,String role,Boolean active,String password){}
    public record Conversion(Long employeeId,String confirmation){}
    private StaffAuthController.StaffUser requireSA(String token){
        var actor=auth.me(token);
        if(!"SA".equals(actor.role()))throw new ResponseStatusException(HttpStatus.FORBIDDEN);
        return actor;
    }
    private static Account row(java.sql.ResultSet r,int i)throws java.sql.SQLException{
        return new Account(r.getLong("id"),r.getString("username"),r.getString("display_name"),r.getString("role"),r.getBoolean("active"),r.getObject("employee_id",Long.class),r.getString("employee_name"));
    }
    private static final String SELECT="""
      SELECT u.id,u.username,u.display_name,u.role,u.active,u.employee_id,e.full_name AS employee_name
      FROM intra.staff_users u LEFT JOIN intra.attendance_employees e ON e.id=u.employee_id
      """;
    @GetMapping
    public List<Account> list(@RequestHeader(value="Authorization",required=false) String token){
        requireSA(token);
        return jdbc.query(SELECT+" ORDER BY u.username",AccessAccountController::row);
    }
    private String username(Input input,boolean creating){
        if(input==null||input.username()==null||input.displayName()==null||input.active()==null||input.role()==null||!ROLES.contains(input.role()))
            throw new ResponseStatusException(HttpStatus.BAD_REQUEST,"Datos de cuenta inválidos");
        String name=input.username().strip().toLowerCase(Locale.ROOT);
        if(!name.matches("[a-z0-9][a-z0-9._@-]{2,99}")||input.displayName().strip().isEmpty()||input.displayName().strip().length()>160)
            throw new ResponseStatusException(HttpStatus.BAD_REQUEST,"Nombre o usuario inválido");
        boolean changed=input.password()!=null&&!input.password().isEmpty();
        if((creating&&!changed)||(changed&&(input.password().length()<6||input.password().getBytes(StandardCharsets.UTF_8).length>72)))
            throw new ResponseStatusException(HttpStatus.BAD_REQUEST,"Contraseña: mínimo 6 caracteres, máximo 72 bytes");
        return name;
    }
    @PostMapping
    @Transactional
    public Map<String,Boolean> create(@RequestHeader(value="Authorization",required=false) String token,@RequestBody Input input){
        var actor=requireSA(token);String name=username(input,true);
        try{
            jdbc.update("""
              INSERT INTO intra.staff_users(username,display_name,role,password_hash,active,employee_id)
              VALUES(?,?,?,?,?,NULL)
              """,name,input.displayName().strip(),input.role(),encoder.encode(input.password()),input.active());
        }catch(DataIntegrityViolationException ex){throw new ResponseStatusException(HttpStatus.CONFLICT,"Usuario ya registrado");}
        org.slf4j.LoggerFactory.getLogger(getClass()).info("SA {} created standalone access account {}",actor.id(),name);
        return Map.of("saved",true);
    }
    @PutMapping("/{id}")
    @Transactional
    public Map<String,Boolean> update(@RequestHeader(value="Authorization",required=false) String token,@PathVariable long id,@RequestBody Input input){
        var actor=requireSA(token);String name=username(input,false);
        var rows=jdbc.query(SELECT+" WHERE u.id=? FOR UPDATE OF u",AccessAccountController::row,id);
        if(rows.isEmpty())throw new ResponseStatusException(HttpStatus.NOT_FOUND);
        Account account=rows.get(0);
        if(account.employeeId()!=null)throw new ResponseStatusException(HttpStatus.CONFLICT,"Editar cuenta vinculada desde Trabajadores");
        if(account.id()==actor.id()&&(!input.active()||!"SA".equals(input.role())))
            throw new ResponseStatusException(HttpStatus.BAD_REQUEST,"No puedes quitar tu propio acceso");
        // Preserve the bootstrap administrator and the actor's current session.
        if("sa".equals(account.username())&&(!"sa".equals(name)||!"SA".equals(input.role())||!input.active()))
            throw new ResponseStatusException(HttpStatus.BAD_REQUEST,"No se puede desactivar o renombrar el SA inicial");
        boolean passwordChanged=input.password()!=null&&!input.password().isEmpty();
        try{
            jdbc.update("UPDATE intra.staff_users SET username=?,display_name=?,role=?,active=? WHERE id=?",name,input.displayName().strip(),input.role(),input.active(),id);
            if(passwordChanged)jdbc.update("UPDATE intra.staff_users SET password_hash=?,failed_attempts=0,locked_until=NULL WHERE id=?",encoder.encode(input.password()),id);
            if(passwordChanged||!account.username().equals(name)||!account.role().equals(input.role())||account.active()!=input.active())
                jdbc.update("DELETE FROM intra.staff_sessions WHERE user_id=?",id);
        }catch(DataIntegrityViolationException ex){throw new ResponseStatusException(HttpStatus.CONFLICT,"Usuario ya registrado");}
        org.slf4j.LoggerFactory.getLogger(getClass()).info("SA {} updated standalone access account id={}",actor.id(),id);
        return Map.of("saved",true);
    }
    @PostMapping("/{id}/convert")
    @Transactional
    public Map<String,Boolean> convert(@RequestHeader(value="Authorization",required=false) String token,@PathVariable long id,@RequestBody Conversion input){
        var actor=requireSA(token);
        if(input==null||input.employeeId()==null||!"CONVERTIR".equals(input.confirmation()))
            throw new ResponseStatusException(HttpStatus.BAD_REQUEST,"Confirmación requerida");
        long employee=input.employeeId();
        // Same lock order as the employee-account workflows.
        var workers=jdbc.query("SELECT id FROM intra.attendance_employees WHERE id=? AND archived_at IS NULL FOR UPDATE",(r,i)->r.getLong(1),employee);
        if(workers.isEmpty())throw new ResponseStatusException(HttpStatus.CONFLICT,"Trabajador no disponible");
        var accounts=jdbc.query(SELECT+" WHERE u.id=? FOR UPDATE OF u",AccessAccountController::row,id);
        if(accounts.isEmpty()||!Long.valueOf(employee).equals(accounts.get(0).employeeId()))
            throw new ResponseStatusException(HttpStatus.CONFLICT,"La vinculación cambió");
        requireNoWorkerHistory(employee,id);
        // Keep the staff ID, credentials, sessions, role and all actor references.
        if(jdbc.update("UPDATE intra.staff_users SET employee_id=NULL WHERE id=? AND employee_id=?",id,employee)!=1)
            throw new ResponseStatusException(HttpStatus.CONFLICT,"La vinculación cambió");
        if(jdbc.update("UPDATE intra.attendance_employees SET active=false,archived_at=now(),updated_at=now() WHERE id=? AND archived_at IS NULL",employee)!=1)
            throw new ResponseStatusException(HttpStatus.CONFLICT,"El trabajador cambió");
        jdbc.update("INSERT INTO intra.access_account_conversions(account_id,employee_id,actor_id,username) VALUES(?,?,?,?)",id,employee,actor.id(),accounts.get(0).username());
        return Map.of("saved",true);
    }

    private void requireNoWorkerHistory(long employee,Long accountId){
        // Never silently detach a real worker or remove historical records.
        Boolean blocked=jdbc.queryForObject("""
          SELECT EXISTS(
            SELECT 1 FROM intra.attendance_employee_device_ids m
              WHERE m.employee_id=? AND NOT EXISTS(
                SELECT 1 FROM intra.device_removal_commands r
                WHERE r.employee_id=m.employee_id AND r.device_id=m.device_id AND r.pin=m.device_user_id
                  AND r.status='confirmed' AND r.result_code=0 AND r.completed_at IS NOT NULL
                  AND NOT EXISTS(
                    SELECT 1 FROM intra.biometric_user_provision_commands q
                    WHERE q.employee_id=m.employee_id AND q.device_id=m.device_id
                      AND q.device_user_id=m.device_user_id AND q.status='confirmed'
                      AND (q.completed_at IS NULL OR q.completed_at>=r.completed_at)))
            UNION ALL SELECT 1 FROM intra.attendance_employee_auth_methods a WHERE a.employee_id=?
              AND NOT EXISTS(SELECT 1 FROM intra.device_removal_commands r
                WHERE r.employee_id=a.employee_id AND r.status='confirmed' AND r.result_code=0)
            UNION ALL SELECT 1 FROM intra.biometric_user_provision_commands q WHERE q.employee_id=?
              AND (q.status IN ('pending','sent') OR (q.status='confirmed' AND NOT EXISTS(
                SELECT 1 FROM intra.device_removal_commands r
                WHERE r.employee_id=q.employee_id AND r.device_id=q.device_id AND r.pin=q.device_user_id
                  AND r.status='confirmed' AND r.result_code=0 AND r.completed_at>q.completed_at)))
            UNION ALL SELECT 1 FROM intra.device_removal_commands WHERE employee_id=? AND status IN ('pending','sent')
            UNION ALL SELECT 1 FROM intra.attendance_manual_adjustments WHERE employee_id=?
            UNION ALL SELECT 1 FROM intra.attendance_observations WHERE employee_id=?
            UNION ALL SELECT 1 FROM intra.payroll_schedules WHERE employee_id=?
            UNION ALL SELECT 1 FROM intra.payroll_incidents WHERE employee_id=?
            UNION ALL SELECT 1 FROM intra.attendance_daily WHERE user_id=?
            UNION ALL SELECT 1 FROM intra.biometric_attendance_events b
              JOIN intra.attendance_employees e ON e.id=?
              WHERE b.device_user_id=e.dni OR b.device_user_id=e.employee_code
                 OR EXISTS(SELECT 1 FROM intra.attendance_employee_device_ids m
                   WHERE m.employee_id=e.id AND m.device_id=b.device_id AND m.device_user_id=b.device_user_id)
          )
          """,Boolean.class,employee,employee,employee,employee,employee,employee,employee,employee,accountId,employee);
        if(!Boolean.FALSE.equals(blocked))throw new ResponseStatusException(HttpStatus.CONFLICT,"Hay historial de asistencia/planilla, órdenes pendientes o vínculos sin eliminación confirmada por el reloj");

    }

    public record NewConversion(Input account,String confirmation){}
    @PostMapping("/from-worker/{employee}")
    @Transactional
    public Map<String,Boolean> convertWithoutAccount(@RequestHeader(value="Authorization",required=false) String token,@PathVariable long employee,@RequestBody NewConversion input){
        var actor=requireSA(token);
        if(input==null||!"CONVERTIR".equals(input.confirmation()))throw new ResponseStatusException(HttpStatus.BAD_REQUEST,"Confirmación requerida");
        Input account=input.account();
        String name=username(account,true);
        var workers=jdbc.query("SELECT id FROM intra.attendance_employees WHERE id=? AND archived_at IS NULL FOR UPDATE",(r,i)->r.getLong(1),employee);
        if(workers.isEmpty())throw new ResponseStatusException(HttpStatus.CONFLICT,"Trabajador no disponible");
        if(!jdbc.query("SELECT id FROM intra.staff_users WHERE employee_id=? FOR UPDATE",(r,i)->r.getLong(1),employee).isEmpty())
            throw new ResponseStatusException(HttpStatus.CONFLICT,"Ya existe una cuenta vinculada; vuelve a abrir el formulario");
        requireNoWorkerHistory(employee,null);
        long id;
        try{
            id=jdbc.queryForObject("""
              INSERT INTO intra.staff_users(username,display_name,role,password_hash,active,employee_id)
              VALUES(?,?,?,?,?,NULL) RETURNING id
              """,Long.class,name,account.displayName().strip(),account.role(),encoder.encode(account.password()),account.active());
        }catch(DataIntegrityViolationException ex){throw new ResponseStatusException(HttpStatus.CONFLICT,"Usuario ya registrado");}
        if(jdbc.update("UPDATE intra.attendance_employees SET active=false,archived_at=now(),updated_at=now() WHERE id=? AND archived_at IS NULL",employee)!=1)
            throw new ResponseStatusException(HttpStatus.CONFLICT,"El trabajador cambió");
        jdbc.update("INSERT INTO intra.access_account_conversions(account_id,employee_id,actor_id,username) VALUES(?,?,?,?)",id,employee,actor.id(),name);
        return Map.of("saved",true);
    }
}

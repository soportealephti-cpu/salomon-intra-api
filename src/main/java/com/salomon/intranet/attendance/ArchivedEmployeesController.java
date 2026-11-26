package com.salomon.intranet.attendance;

import com.salomon.intranet.auth.StaffAuthController;
import org.springframework.dao.DataIntegrityViolationException;
import org.springframework.http.HttpStatus;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.transaction.annotation.Transactional;
import org.springframework.web.bind.annotation.*;
import org.springframework.web.server.ResponseStatusException;
import java.time.OffsetDateTime;
import java.util.List;

@RestController
@RequestMapping("/api/v1/attendance/employees")
public class ArchivedEmployeesController {
    private final JdbcTemplate jdbc;
    private final StaffAuthController auth;
    public ArchivedEmployeesController(JdbcTemplate jdbc, StaffAuthController auth) {
        this.jdbc=jdbc; this.auth=auth;
    }
    private void requireSA(String authorization) {
        if(!"SA".equals(auth.me(authorization).role()))
            throw new ResponseStatusException(HttpStatus.FORBIDDEN);
    }
    public record Archived(long id,String fullName,String dni,String area,OffsetDateTime archivedAt,String deletionBlock) {}
    private static final String BLOCK = """
        CASE
          WHEN EXISTS(SELECT 1 FROM intra.biometric_attendance_events b WHERE b.device_user_id=e.dni OR EXISTS(
            SELECT 1 FROM intra.attendance_employee_device_ids m WHERE m.employee_id=e.id
            AND m.device_id=b.device_id AND m.device_user_id=b.device_user_id))
            OR EXISTS(SELECT 1 FROM intra.attendance_manual_adjustments a WHERE a.employee_id=e.id)
            OR EXISTS(SELECT 1 FROM intra.attendance_observations o WHERE o.employee_id=e.id)
            OR EXISTS(SELECT 1 FROM intra.attendance_outings o WHERE o.employee_id=e.id)
            OR EXISTS(SELECT 1 FROM intra.payroll_incidents i WHERE i.employee_id=e.id)
            OR EXISTS(SELECT 1 FROM intra.payroll_schedules s WHERE s.employee_id=e.id)
            THEN 'Tiene historial de asistencia o planilla.'
          WHEN EXISTS(SELECT 1 FROM intra.staff_users u WHERE u.employee_id=e.id)
            THEN 'Tiene una cuenta web asociada.'
          WHEN EXISTS(SELECT 1 FROM intra.attendance_employee_device_ids m WHERE m.employee_id=e.id)
            THEN 'Conserva una vinculación biométrica.'
          WHEN EXISTS(SELECT 1 FROM intra.biometric_user_provision_commands q WHERE q.employee_id=e.id)
            OR EXISTS(SELECT 1 FROM intra.device_removal_commands r WHERE r.employee_id=e.id)
            THEN 'Tiene historial de comandos biométricos.'
          ELSE NULL
        END
        """;
    @GetMapping("/trash")
    public List<Archived> trash(@RequestHeader(value="Authorization",required=false) String authorization) {
        requireSA(authorization);
        return jdbc.query("SELECT e.id,e.full_name,e.dni,e.area,e.archived_at,"+BLOCK+
          " AS deletion_block FROM intra.attendance_employees e WHERE e.archived_at IS NOT NULL ORDER BY e.archived_at DESC,e.id DESC",
          (rs,i)->new Archived(rs.getLong("id"),rs.getString("full_name"),rs.getString("dni"),
            rs.getString("area"),rs.getObject("archived_at",OffsetDateTime.class),rs.getString("deletion_block")));
    }
    @PostMapping("/{id}/restore")
    @Transactional
    public void restore(@RequestHeader(value="Authorization",required=false) String authorization,@PathVariable long id) {
        requireSA(authorization);
        // Restore to the directory, not to active duty or web login.
        if(jdbc.update("UPDATE intra.attendance_employees SET archived_at=NULL,active=false,updated_at=now() WHERE id=? AND archived_at IS NOT NULL",id)!=1)
            throw new ResponseStatusException(HttpStatus.NOT_FOUND,"Trabajador archivado no encontrado");
    }
    @DeleteMapping("/{id}/permanent")
    @ResponseStatus(HttpStatus.NO_CONTENT)
    @Transactional
    public void purge(@RequestHeader(value="Authorization",required=false) String authorization,@PathVariable long id,
                      @RequestParam(defaultValue="false") boolean confirmed) {
        requireSA(authorization);
        if(!confirmed)throw new ResponseStatusException(HttpStatus.BAD_REQUEST,"Confirma la eliminación definitiva");
        var rows=jdbc.query("SELECT id FROM intra.attendance_employees WHERE id=? AND archived_at IS NOT NULL AND NOT active FOR UPDATE",
          (rs,i)->rs.getLong(1),id);
        if(rows.isEmpty())throw new ResponseStatusException(HttpStatus.NOT_FOUND,"Trabajador archivado no encontrado");
        String reason=jdbc.queryForObject("SELECT "+BLOCK+" FROM intra.attendance_employees e WHERE e.id=?",String.class,id);
        if(reason!=null)throw new ResponseStatusException(HttpStatus.CONFLICT,reason);
        try {
            // The final predicate rechecks dependencies. Foreign keys remain enabled.
            int deleted=jdbc.update("DELETE FROM intra.attendance_employees e WHERE e.id=? AND e.archived_at IS NOT NULL AND NOT e.active AND ("+BLOCK+") IS NULL",id);
            if(deleted!=1)throw new ResponseStatusException(HttpStatus.CONFLICT,"El registro cambió; actualiza la papelera");
        } catch(DataIntegrityViolationException ex) {
            throw new ResponseStatusException(HttpStatus.CONFLICT,"Existen registros relacionados que deben conservarse");
        }
        org.slf4j.LoggerFactory.getLogger(ArchivedEmployeesController.class).info("Permanent archived employee deletion: id={}",id);
    }

    public record DeletionPreview(String fullName,java.util.Map<String,Long> counts,String blockedReason) {}
    public record ForceRequest(String confirmation,java.util.Map<String,Long> counts) {}
    private record Related(String key,String table,String predicate) {}
    // All predicates are scoped to one employee in this company's database.
    // Raw punches may have no employee foreign key; never delete a PIN linked to another worker.
    private static final List<Related> RELATED=List.of(
        new Related("marcaciones","biometric_attendance_events","EXISTS(SELECT 1 FROM intra.attendance_employees e WHERE e.id=? AND (EXISTS(SELECT 1 FROM intra.attendance_employee_device_ids m WHERE m.employee_id=e.id AND m.device_id=t.device_id AND m.device_user_id=t.device_user_id) OR (t.device_user_id=e.dni AND NOT EXISTS(SELECT 1 FROM intra.attendance_employee_device_ids m WHERE m.device_id=t.device_id AND m.device_user_id=t.device_user_id AND m.employee_id<>e.id))))"),
        new Related("ajustes","attendance_manual_adjustments","t.employee_id=?"),
        new Related("observaciones","attendance_observations","t.employee_id=?"),
        new Related("horarios","payroll_schedules","t.employee_id=?"),
        new Related("incidencias","payroll_incidents","t.employee_id=?"),
        new Related("ordenesEnvio","biometric_user_provision_commands","t.employee_id=?"),
        new Related("ordenesEliminacion","device_removal_commands","t.employee_id=?"),
        new Related("metodos","attendance_employee_auth_methods","t.employee_id=?"),
        new Related("vinculaciones","attendance_employee_device_ids","t.employee_id=?"),
        new Related("asistenciaWeb","attendance_daily","t.user_id IN (SELECT id FROM intra.staff_users WHERE employee_id=?)"),
        new Related("sesiones","staff_sessions","t.user_id IN (SELECT id FROM intra.staff_users WHERE employee_id=?)"),
        new Related("cuentasWeb","staff_users","t.employee_id=?")
    );
    private String hardBlock(long id) {
        return jdbc.queryForObject("""
            WITH u AS (SELECT id,role,active FROM intra.staff_users WHERE employee_id=?)
            SELECT CASE
            WHEN EXISTS(SELECT 1 FROM intra.attendance_outings o WHERE o.employee_id=?
                OR o.actor_id IN (SELECT id FROM u) OR o.return_actor_id IN (SELECT id FROM u))
              THEN 'Tiene registros de salidas temporales o su auditoría; deben conservarse.'
            WHEN EXISTS(SELECT 1 FROM u WHERE active OR role='SA')
              THEN 'La cuenta vinculada está activa o tiene rol SA. No se puede eliminar desde la papelera.'
            WHEN EXISTS(SELECT 1 FROM intra.biometric_user_provision_commands WHERE employee_id=? AND status IN ('pending','sent'))
              OR EXISTS(SELECT 1 FROM intra.device_removal_commands WHERE employee_id=? AND status IN ('pending','sent'))
              THEN 'Hay órdenes biométricas pendientes o enviadas. Espera su resolución antes de eliminar.'
            WHEN EXISTS(SELECT 1 FROM intra.attendance_manual_adjustments WHERE actor_id IN (SELECT id FROM u) AND employee_id<>?)
              OR EXISTS(SELECT 1 FROM intra.attendance_observations WHERE actor_id IN (SELECT id FROM u) AND employee_id<>?)
              OR EXISTS(SELECT 1 FROM intra.payroll_schedules WHERE actor_id IN (SELECT id FROM u) AND employee_id<>?)
              OR EXISTS(SELECT 1 FROM intra.payroll_incidents WHERE actor_id IN (SELECT id FROM u) AND employee_id<>?)
              OR EXISTS(SELECT 1 FROM intra.device_removal_commands WHERE actor_id IN (SELECT id FROM u) AND employee_id<>?)
              OR EXISTS(SELECT 1 FROM intra.biometric_device_admins WHERE actor_id IN (SELECT id FROM u))
              OR EXISTS(SELECT 1 FROM intra.biometric_device_admin_commands WHERE actor_id IN (SELECT id FROM u))
              THEN 'La cuenta figura como responsable de operaciones de otros trabajadores o administradores. Debe conservarse su auditoría.'
            ELSE NULL END
            """,String.class,id,id,id,id,id,id,id,id,id);
    }
    private DeletionPreview preview(long id) {
        var rows=jdbc.query("SELECT full_name FROM intra.attendance_employees WHERE id=? AND archived_at IS NOT NULL AND NOT active",
            (rs,i)->rs.getString(1),id);
        if(rows.isEmpty())throw new ResponseStatusException(HttpStatus.NOT_FOUND,"Trabajador archivado no encontrado");
        var counts=new java.util.LinkedHashMap<String,Long>();
        for(var r:RELATED) counts.put(r.key(),jdbc.queryForObject("SELECT count(*) FROM intra."+r.table()+" t WHERE "+r.predicate(),Long.class,id));
        return new DeletionPreview(rows.get(0),counts,hardBlock(id));
    }
    @GetMapping("/{id}/deletion-preview")
    @Transactional(readOnly=true)
    public DeletionPreview deletionPreview(@RequestHeader(value="Authorization",required=false) String authorization,@PathVariable long id) {
        requireSA(authorization);
        return preview(id);
    }
    @PostMapping("/{id}/force-permanent")
    @ResponseStatus(HttpStatus.NO_CONTENT)
    @Transactional(isolation=org.springframework.transaction.annotation.Isolation.SERIALIZABLE)
    public void forcePermanent(@RequestHeader(value="Authorization",required=false) String authorization,
            @PathVariable long id,@RequestBody ForceRequest request) {
        requireSA(authorization);
        if(request==null||!"ELIMINAR".equals(request.confirmation())||request.counts()==null)
            throw new ResponseStatusException(HttpStatus.BAD_REQUEST,"Confirmación y vista previa requeridas");
        if(jdbc.query("SELECT id FROM intra.attendance_employees WHERE id=? AND archived_at IS NOT NULL AND NOT active FOR UPDATE",
            (rs,i)->rs.getLong(1),id).isEmpty())
            throw new ResponseStatusException(HttpStatus.NOT_FOUND,"Trabajador archivado no encontrado");
        jdbc.query("SELECT id FROM intra.staff_users WHERE employee_id=? FOR UPDATE",(rs,i)->rs.getLong(1),id);
        var current=preview(id);
        if(current.blockedReason()!=null)throw new ResponseStatusException(HttpStatus.CONFLICT,current.blockedReason());
        if(!current.counts().equals(request.counts()))
            throw new ResponseStatusException(HttpStatus.CONFLICT,"Las dependencias cambiaron. Vuelve a consultar la vista previa.");
        try {
            for(var r:RELATED) {
                int deleted=jdbc.update("DELETE FROM intra."+r.table()+" t WHERE "+r.predicate(),id);
                if(deleted!=current.counts().get(r.key()))
                    throw new ResponseStatusException(HttpStatus.CONFLICT,"Los registros cambiaron. No se eliminó ningún dato.");
            }
            if(jdbc.update("DELETE FROM intra.attendance_employees WHERE id=? AND archived_at IS NOT NULL AND NOT active",id)!=1)
                throw new ResponseStatusException(HttpStatus.CONFLICT,"El registro cambió");
        } catch(DataIntegrityViolationException ex) {
            throw new ResponseStatusException(HttpStatus.CONFLICT,"Existen dependencias protegidas. No se eliminó ningún dato.");
        }
        org.slf4j.LoggerFactory.getLogger(ArchivedEmployeesController.class).info("SA {} purged archived employee id={} counts={}",auth.me(authorization).id(),id,current.counts());
    }
}

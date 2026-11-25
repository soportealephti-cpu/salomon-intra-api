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
}

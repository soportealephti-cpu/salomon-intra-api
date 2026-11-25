package com.salomon.intranet.attendance;

import org.springframework.beans.factory.annotation.Value;
import org.springframework.http.*;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.web.bind.annotation.*;
import org.springframework.web.server.ResponseStatusException;
import java.nio.charset.StandardCharsets;
import java.security.MessageDigest;
import java.util.List;

@RestController
@RequestMapping("/internal/marcaciones")
public class CentralAttendanceController {
    private final JdbcTemplate jdbc;
    private final AdmsAttendanceService attendance;
    private final String token;
    private final AdmsUserProvisionService provision;
    private final AdmsUserSyncService sync;
    private final DeviceRemovalController removal;
    private final AdmsDeviceAdminService admins;
    public CentralAttendanceController(JdbcTemplate jdbc, AdmsAttendanceService attendance, AdmsUserProvisionService provision, AdmsUserSyncService sync, DeviceRemovalController removal, AdmsDeviceAdminService admins,
        @Value("${MARCACIONES_CENTRAL_TOKEN:}") String token) {
        this.jdbc=jdbc; this.attendance=attendance; this.token=token; this.provision=provision; this.sync=sync; this.removal=removal; this.admins=admins;
    }
    private void authorize(String supplied) {
        if(token.isBlank() || supplied==null || !MessageDigest.isEqual(token.getBytes(StandardCharsets.UTF_8),supplied.getBytes(StandardCharsets.UTF_8)))
            throw new ResponseStatusException(HttpStatus.FORBIDDEN);
    }
    private boolean owns(String serial,String pin) {
        return Boolean.TRUE.equals(jdbc.queryForObject("""
            SELECT EXISTS(SELECT 1 FROM intra.attendance_employee_device_ids x
            JOIN intra.biometric_devices d ON d.id=x.device_id
            JOIN intra.attendance_employees e ON e.id=x.employee_id
            WHERE d.serial_number=? AND d.active AND x.device_user_id=? AND e.active AND e.archived_at IS NULL)
            """,Boolean.class,serial,pin));
    }
    @GetMapping("/owner")
    public boolean owner(@RequestHeader(value="X-Central-Token",required=false) String supplied,
        @RequestParam String serial,@RequestParam String pin) {
        authorize(supplied); return owns(serial,pin);
    }
    @PostMapping(value="/attlog",produces=MediaType.TEXT_PLAIN_VALUE)
    @org.springframework.transaction.annotation.Transactional
    public String receive(@RequestHeader(value="X-Central-Token",required=false) String supplied,
        @RequestParam String serial,@RequestBody byte[] body) {
        authorize(supplied);
        var punches=AdmsAttendanceService.parse(body);
        for(var punch:punches) if(!owns(serial,punch.pin())) throw new ResponseStatusException(HttpStatus.CONFLICT,"PIN no vinculado");
        return attendance.receive(serial,body);
    }

    @GetMapping("/claimed")
    public boolean claimed(@RequestHeader(value="X-Central-Token",required=false) String supplied,
        @RequestParam String serial,@RequestParam String pin) {
        authorize(supplied);
        return Boolean.TRUE.equals(jdbc.queryForObject("""
          SELECT EXISTS(SELECT 1 FROM intra.attendance_employees WHERE dni=?
          UNION ALL SELECT 1 FROM intra.attendance_employee_device_ids x JOIN intra.biometric_devices d ON d.id=x.device_id WHERE d.serial_number=? AND x.device_user_id=?
          UNION ALL SELECT 1 FROM intra.biometric_device_admins a JOIN intra.biometric_devices d ON d.id=a.device_id WHERE d.serial_number=? AND a.pin=?)
          """,Boolean.class,pin,serial,pin,serial,pin));
    }
    @PostMapping("/sync-pin")
    @org.springframework.transaction.annotation.Transactional
    public boolean syncPin(@RequestHeader(value="X-Central-Token",required=false) String supplied,
        @RequestParam String serial,@RequestParam String pin) {
        authorize(supplied);
        int changed=jdbc.update("""
          INSERT INTO intra.attendance_employee_device_ids(employee_id,device_id,device_user_id)
          SELECT e.id,d.id,? FROM intra.attendance_employees e CROSS JOIN intra.biometric_devices d
          WHERE e.dni=? AND e.active AND e.archived_at IS NULL AND d.serial_number=? AND d.active
          AND NOT EXISTS(SELECT 1 FROM intra.biometric_device_admins a WHERE a.device_id=d.id AND a.pin=?)
          ON CONFLICT DO NOTHING
          """,pin,pin,serial,pin);
        return changed>0 || owns(serial,pin);
    }

    /** Live registry lookup: inactive or absent devices are not authorized. */
    @GetMapping("/device-active")
    public boolean deviceActive(@RequestHeader(value="X-Central-Token",required=false) String supplied,
                                @RequestParam String serial) {
        authorize(supplied);
        if(!serial.matches("[A-Za-z0-9_-]{4,64}"))
            throw new ResponseStatusException(HttpStatus.BAD_REQUEST,"Serie inválida");
        return Boolean.TRUE.equals(jdbc.queryForObject(
            "SELECT EXISTS(SELECT 1 FROM intra.biometric_devices WHERE serial_number=? AND active)",
            Boolean.class,serial));
    }

    private long device(String serial) {
        var ids=jdbc.query("SELECT id FROM intra.biometric_devices WHERE serial_number=? AND active",(rs,i)->rs.getLong(1),serial);
        if(ids.isEmpty()) throw new ResponseStatusException(HttpStatus.NOT_FOUND,"Equipo no registrado");
        return ids.get(0);
    }
    @PostMapping("/contact")
    public void contact(@RequestHeader(value="X-Central-Token",required=false) String supplied,@RequestParam String serial) {
        authorize(supplied); device(serial);
        jdbc.update("""
          INSERT INTO intra.biometric_device_contacts(serial_number,last_endpoint) VALUES(?,'central')
          ON CONFLICT(serial_number) DO UPDATE SET last_seen_at=now(),last_endpoint='central',contact_count=intra.biometric_device_contacts.contact_count+1
          """,serial);
    }
    @PostMapping(value="/poll",produces=MediaType.TEXT_PLAIN_VALUE)
    @org.springframework.transaction.annotation.Transactional
    public String poll(@RequestHeader(value="X-Central-Token",required=false) String supplied,@RequestParam String serial) {
        authorize(supplied);
        long id=device(serial);
        String command=removal.next(id);
        if(command!=null) return command;
        command=admins.nextCommand(id);
        if(command!=null) return command;
        command=provision.nextCommand(id);
        if(command!=null) return command;
        var rows=jdbc.query("""
          UPDATE intra.biometric_user_sync_commands c SET status='sent',sent_at=now()
          WHERE id=(SELECT id FROM intra.biometric_user_sync_commands WHERE device_id=?
          AND created_at>now()-interval '10 minutes'
          AND (status='pending' OR (status='sent' AND sent_at<now()-interval '30 seconds'))
          ORDER BY id LIMIT 1 FOR UPDATE SKIP LOCKED) RETURNING c.id
          """,(rs,i)->rs.getLong(1),id);
        return rows.isEmpty()?"OK\n":"C:"+rows.get(0)+":DATA QUERY USERINFO\n";
    }
    @PostMapping(value="/confirm",produces=MediaType.TEXT_PLAIN_VALUE)
    public String confirm(@RequestHeader(value="X-Central-Token",required=false) String supplied,
         @RequestParam String serial,@RequestParam long id,@RequestParam int result) {
        authorize(supplied); if(id>=DeviceRemovalController.OFFSET) return removal.complete(device(serial),id,result); return sync.confirm(serial,id,result,"");
    }
    @GetMapping("/eligible")
    public boolean eligible(@RequestHeader(value="X-Central-Token",required=false) String supplied,
        @RequestParam String serial,@RequestParam String pin) {
        authorize(supplied);
        return Boolean.TRUE.equals(jdbc.queryForObject("""
          SELECT EXISTS(SELECT 1 FROM intra.attendance_employees e CROSS JOIN intra.biometric_devices d
          WHERE e.dni=? AND e.active AND e.archived_at IS NULL AND d.serial_number=? AND d.active
          AND NOT EXISTS(SELECT 1 FROM intra.biometric_device_admins a WHERE a.device_id=d.id AND a.pin=?)
          AND NOT EXISTS(SELECT 1 FROM intra.attendance_employee_device_ids m WHERE m.device_id=d.id AND m.device_user_id=? AND m.employee_id<>e.id))
          """,Boolean.class,pin,serial,pin,pin));
    }

    @GetMapping("/removable")
    public boolean removable(@RequestHeader(value="X-Central-Token",required=false) String supplied,@RequestParam String serial,@RequestParam String pin) {
        authorize(supplied);
        return Boolean.TRUE.equals(jdbc.queryForObject("""
          SELECT EXISTS(SELECT 1 FROM intra.device_removal_commands q JOIN intra.biometric_devices d ON d.id=q.device_id
          JOIN intra.attendance_employees e ON e.id=q.employee_id
          JOIN intra.attendance_employee_device_ids m ON m.employee_id=e.id AND m.device_id=d.id AND m.device_user_id=q.pin
          WHERE d.serial_number=? AND d.active AND q.pin=? AND q.status='sent' AND NOT e.active
          AND NOT EXISTS(SELECT 1 FROM intra.biometric_device_admins a WHERE a.device_id=d.id AND a.pin=q.pin)
          AND NOT EXISTS(SELECT 1 FROM intra.biometric_user_provision_commands b WHERE b.device_id=d.id AND b.device_user_id=q.pin AND b.status IN ('pending','sent')))
          """,Boolean.class,serial,pin));
    }
    @GetMapping("/admin-eligible")
    public boolean adminEligible(@RequestHeader(value="X-Central-Token",required=false) String supplied,@RequestParam String serial,@RequestParam String pin) {
        authorize(supplied);
        return Boolean.TRUE.equals(jdbc.queryForObject("""
          SELECT EXISTS(SELECT 1 FROM intra.biometric_device_admin_commands q JOIN intra.biometric_devices d ON d.id=q.device_id
          WHERE d.serial_number=? AND d.active AND q.pin=? AND q.status='sent'
          AND NOT EXISTS(SELECT 1 FROM intra.attendance_employees e WHERE e.dni=q.pin)
          AND NOT EXISTS(SELECT 1 FROM intra.attendance_employee_device_ids m WHERE m.device_id=d.id AND m.device_user_id=q.pin)
          AND NOT EXISTS(SELECT 1 FROM intra.biometric_user_provision_commands b WHERE b.device_id=d.id AND b.device_user_id=q.pin AND b.status IN ('pending','sent')))
          """,Boolean.class,serial,pin));
    }
}

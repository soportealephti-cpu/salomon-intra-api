package com.salomon.intranet.attendance;

import com.salomon.intranet.auth.StaffAuthController;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.http.HttpStatus;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.transaction.annotation.Transactional;
import org.springframework.web.bind.annotation.*;
import org.springframework.web.server.ResponseStatusException;
import java.util.List;

@RestController
@RequestMapping("/api/v1/attendance/device-removal")
public class DeviceRemovalController {
    static final long OFFSET=3_000_000_000L;
    private final JdbcTemplate jdbc;
    private final StaffAuthController auth;
    private final boolean enabled;
    public DeviceRemovalController(JdbcTemplate jdbc,StaffAuthController auth,@Value("${MARCACIONES_CENTRAL_ENABLED:false}") boolean enabled){
        this.jdbc=jdbc;this.auth=auth;this.enabled=enabled;
    }
    record Status(long id,String pin,String status,Integer resultCode){}
    record Request(long deviceId){}
    private long actor(String token){var a=auth.me(token);if(!"SA".equals(a.role()))throw new ResponseStatusException(HttpStatus.FORBIDDEN);return a.id();}
    @PostMapping("/{employeeId}")
    @ResponseStatus(HttpStatus.ACCEPTED)
    @Transactional
    public Status enqueue(@RequestHeader(value="Authorization",required=false) String token,@PathVariable long employeeId,@RequestBody Request request){
        long actor=actor(token);
        if(!enabled)throw new ResponseStatusException(HttpStatus.CONFLICT,"Activa primero la API central");
        if(request==null||request.deviceId()<1)throw new ResponseStatusException(HttpStatus.BAD_REQUEST);
        var employees=jdbc.query("SELECT active FROM intra.attendance_employees WHERE id=? FOR UPDATE",(r,i)->r.getBoolean(1),employeeId);
        if(employees.isEmpty()||employees.get(0))throw new ResponseStatusException(HttpStatus.CONFLICT,"Desactiva al trabajador primero");
        var pins=jdbc.query("""
          SELECT m.device_user_id FROM intra.attendance_employee_device_ids m JOIN intra.biometric_devices d ON d.id=m.device_id
          WHERE m.employee_id=? AND m.device_id=? AND d.active
          AND NOT EXISTS(SELECT 1 FROM intra.biometric_device_admins a WHERE a.device_id=m.device_id AND a.pin=m.device_user_id)
          """,(r,i)->r.getString(1),employeeId,request.deviceId());
        if(pins.size()!=1||!pins.get(0).matches("[0-9]{1,15}"))throw new ResponseStatusException(HttpStatus.CONFLICT,"PIN vinculado no apto");
        String pin=pins.get(0);
        var ids=jdbc.query("""
          INSERT INTO intra.device_removal_commands(employee_id,device_id,pin,actor_id)
          VALUES(?,?,?,?) ON CONFLICT DO NOTHING RETURNING id
          """,(r,i)->r.getLong(1),employeeId,request.deviceId(),pin,actor);
        if(ids.isEmpty())throw new ResponseStatusException(HttpStatus.CONFLICT,"Ya existe una orden pendiente");
        return new Status(ids.get(0),pin,"pending",null);
    }
    @GetMapping("/{employeeId}")
    public Status latest(@RequestHeader(value="Authorization",required=false) String token,@PathVariable long employeeId,@RequestParam long deviceId){
        actor(token);
        var rows=jdbc.query("SELECT id,pin,status,result_code FROM intra.device_removal_commands WHERE employee_id=? AND device_id=? ORDER BY id DESC LIMIT 1",
          (r,i)->new Status(r.getLong(1),r.getString(2),r.getString(3),r.getObject(4,Integer.class)),employeeId,deviceId);
        return rows.isEmpty()?new Status(0,"","idle",null):rows.get(0);
    }
    @Transactional
    public String next(long deviceId){
        jdbc.update("UPDATE intra.device_removal_commands SET status='expired',completed_at=now() WHERE device_id=? AND status IN ('pending','sent') AND created_at<now()-interval '24 hours'",deviceId);
        var rows=jdbc.query("""
          UPDATE intra.device_removal_commands q SET status='sent',sent_at=now()
          WHERE id=(SELECT id FROM intra.device_removal_commands WHERE device_id=?
          AND (status='pending' OR (status='sent' AND sent_at<now()-interval '60 seconds'))
          ORDER BY id LIMIT 1 FOR UPDATE SKIP LOCKED) RETURNING id,pin
          """,(r,i)->"C:"+(OFFSET+r.getLong(1))+":DATA DELETE USERINFO PIN="+r.getString(2)+"\n",deviceId);
        return rows.isEmpty()?null:rows.get(0);
    }
    @Transactional
    public String complete(long deviceId,long id,int code){
        jdbc.update("UPDATE intra.device_removal_commands SET status=?,result_code=?,completed_at=now() WHERE id=? AND device_id=? AND status='sent'",code==0?"confirmed":"failed",code,id-OFFSET,deviceId);
        // Keep the employee-device link for historic attendance and audit.
        return "OK\n";
    }
}

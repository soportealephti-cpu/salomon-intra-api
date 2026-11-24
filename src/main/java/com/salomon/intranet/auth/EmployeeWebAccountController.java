package com.salomon.intranet.auth;

import org.springframework.http.HttpStatus;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.security.crypto.bcrypt.BCryptPasswordEncoder;
import org.springframework.transaction.annotation.Transactional;
import org.springframework.web.bind.annotation.*;
import org.springframework.web.server.ResponseStatusException;
import org.springframework.dao.DataIntegrityViolationException;
import java.util.*;

@RestController
@RequestMapping("/api/v1/attendance/web-accounts")
public class EmployeeWebAccountController {
    private final JdbcTemplate jdbc;
    private final StaffAuthController auth;
    private final BCryptPasswordEncoder encoder = new BCryptPasswordEncoder();
    public EmployeeWebAccountController(JdbcTemplate jdbc, StaffAuthController auth) {
        this.jdbc=jdbc; this.auth=auth;
    }
    record Account(long id,String username,String role,boolean active) {}
    public record Input(String username,String password,String role,Boolean active) {}
    private void requireSA(String token) {
        if (!"SA".equals(auth.me(token).role())) throw new ResponseStatusException(HttpStatus.FORBIDDEN);
    }
    @GetMapping("/{employeeId}")
    public Map<String,Object> get(@RequestHeader(value="Authorization",required=false) String token,@PathVariable long employeeId) {
        requireSA(token);
        var rows=jdbc.query("SELECT id,username,role,active FROM intra.staff_users WHERE employee_id=?",
            (rs,i)->new Account(rs.getLong(1),rs.getString(2),rs.getString(3),rs.getBoolean(4)),employeeId);
        return rows.isEmpty()?Map.of("exists",false):Map.of("exists",true,"account",rows.get(0));
    }
    @PostMapping("/{employeeId}")
    @Transactional
    public Map<String,Object> save(@RequestHeader(value="Authorization",required=false) String token,
                                  @PathVariable long employeeId,@RequestBody Input input) {
        requireSA(token);
        if(input==null || input.username()==null || input.role()==null || input.active()==null ||
            !Set.of("SA","ADMIN","GERENCIA","RECEPCION","SOPORTE_TI").contains(input.role()))
            throw new ResponseStatusException(HttpStatus.BAD_REQUEST,"Datos inválidos");
        String username=input.username().strip().toLowerCase(Locale.ROOT);
        if(!username.matches("[a-z0-9][a-z0-9._@-]{2,99}"))
            throw new ResponseStatusException(HttpStatus.BAD_REQUEST,"Usuario inválido");
        String password=input.password();
        boolean change=password!=null && !password.isEmpty();
        if(change && (password.length()<6 || password.getBytes(java.nio.charset.StandardCharsets.UTF_8).length>72))
            throw new ResponseStatusException(HttpStatus.BAD_REQUEST,"Contraseña: mínimo 6 caracteres y máximo 72 bytes");
        var workers=jdbc.query("SELECT full_name,area,company,active FROM intra.attendance_employees WHERE id=? FOR UPDATE",
            (rs,i)->new Object[]{rs.getString(1),rs.getString(2),rs.getString(3),rs.getBoolean(4)},employeeId);
        if(workers.isEmpty()) throw new ResponseStatusException(HttpStatus.NOT_FOUND);
        if(input.active() && !Boolean.TRUE.equals(workers.get(0)[3]))
            throw new ResponseStatusException(HttpStatus.BAD_REQUEST,"Trabajador inactivo");
        var existing=jdbc.query("SELECT id,username,role,active FROM intra.staff_users WHERE employee_id=? FOR UPDATE",
            (rs,i)->new Account(rs.getLong(1),rs.getString(2),rs.getString(3),rs.getBoolean(4)),employeeId);
        try {
            if(existing.isEmpty()) {
                if(!change) throw new ResponseStatusException(HttpStatus.BAD_REQUEST,"Contraseña obligatoria");
                var w=workers.get(0);
                jdbc.update("""
                    INSERT INTO intra.staff_users(username,display_name,role,password_hash,active,employee_id,area,company)
                    VALUES (?,?,?,?,?,?,?,?)
                    """,username,w[0],input.role(),encoder.encode(password),input.active(),employeeId,w[1],w[2]);
            } else {
                var account=existing.get(0);
                if(account.id()==auth.me(token).id() && (!input.active() || !"SA".equals(input.role())))
                    throw new ResponseStatusException(HttpStatus.BAD_REQUEST,"No puedes quitar tu propio acceso");
                if (!change && account.username().equals(username) && account.role().equals(input.role()) && account.active()==input.active()) return Map.of("saved",true);
                jdbc.update("UPDATE intra.staff_users SET username=?,role=?,active=? WHERE id=?",username,input.role(),input.active(),account.id());
                if(change) jdbc.update("UPDATE intra.staff_users SET password_hash=?,failed_attempts=0,locked_until=NULL WHERE id=?",encoder.encode(password),account.id());
                jdbc.update("DELETE FROM intra.staff_sessions WHERE user_id=?",account.id());
            }
        } catch(DataIntegrityViolationException ex) {
            throw new ResponseStatusException(HttpStatus.CONFLICT,"Usuario ya registrado");
        }
        return Map.of("saved",true);
    }
}

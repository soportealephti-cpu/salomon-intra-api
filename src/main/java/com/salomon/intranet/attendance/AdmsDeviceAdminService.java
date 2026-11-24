package com.salomon.intranet.attendance;

import com.salomon.intranet.auth.StaffAuthController;
import org.springframework.http.HttpStatus;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;
import org.springframework.web.server.ResponseStatusException;

import javax.crypto.Cipher;
import javax.crypto.spec.GCMParameterSpec;
import javax.crypto.spec.SecretKeySpec;
import java.nio.charset.StandardCharsets;
import java.security.SecureRandom;
import java.util.Base64;
import java.util.List;
import java.util.Map;

@Service
public class AdmsDeviceAdminService {
    // Different namespace from worker provisioning (1_000_000_000).
    private static final long OFFSET = 2_000_000_000L;
    private final JdbcTemplate jdbc;
    private final StaffAuthController auth;
    private final SecureRandom random = new SecureRandom();

    public AdmsDeviceAdminService(JdbcTemplate jdbc, StaffAuthController auth) {
        this.jdbc = jdbc;
        this.auth = auth;
    }

    public record AdminRequest(long deviceId, String pin, String name, String password) {}
    public record AdminStatus(long deviceId, String pin, String name,
                              String status, Integer resultCode, String updatedAt) {}
    private record Outbound(long id, String pin, String name, String encrypted) {}

    private long requireSA(String token) {
        var viewer = auth.me(token);
        if (!"SA".equals(viewer.role())) throw new ResponseStatusException(HttpStatus.FORBIDDEN, "Solo SA");
        return viewer.id();
    }

    private SecretKeySpec key() {
        String encoded = System.getenv("SALOMON_DEVICE_ADMIN_KEY");
        if (encoded == null || encoded.isBlank())
            throw new ResponseStatusException(HttpStatus.SERVICE_UNAVAILABLE,
                "Configura SALOMON_DEVICE_ADMIN_KEY para cifrar las contraseñas administrativas");
        try {
            byte[] bytes = Base64.getDecoder().decode(encoded);
            if (bytes.length != 32) throw new IllegalArgumentException();
            return new SecretKeySpec(bytes, "AES");
        } catch (IllegalArgumentException e) {
            throw new ResponseStatusException(HttpStatus.SERVICE_UNAVAILABLE,
                "SALOMON_DEVICE_ADMIN_KEY debe contener 32 bytes en Base64");
        }
    }

    private String encrypt(String password) {
        try {
            byte[] nonce = new byte[12];
            random.nextBytes(nonce);
            Cipher cipher = Cipher.getInstance("AES/GCM/NoPadding");
            cipher.init(Cipher.ENCRYPT_MODE, key(), new GCMParameterSpec(128, nonce));
            byte[] encrypted = cipher.doFinal(password.getBytes(StandardCharsets.UTF_8));
            byte[] complete = new byte[nonce.length + encrypted.length];
            System.arraycopy(nonce, 0, complete, 0, nonce.length);
            System.arraycopy(encrypted, 0, complete, nonce.length, encrypted.length);
            return Base64.getEncoder().encodeToString(complete);
        } catch (Exception e) {
            if (e instanceof ResponseStatusException status) throw status;
            throw new IllegalStateException("No se pudo cifrar la credencial administrativa", e);
        }
    }

    private String decrypt(String encoded) {
        try {
            byte[] complete = Base64.getDecoder().decode(encoded);
            if (complete.length < 29) throw new IllegalArgumentException();
            byte[] nonce = java.util.Arrays.copyOfRange(complete, 0, 12);
            Cipher cipher = Cipher.getInstance("AES/GCM/NoPadding");
            cipher.init(Cipher.DECRYPT_MODE, key(), new GCMParameterSpec(128, nonce));
            return new String(cipher.doFinal(complete, 12, complete.length - 12), StandardCharsets.UTF_8);
        } catch (Exception e) {
            if (e instanceof ResponseStatusException status) throw status;
            throw new IllegalStateException("No se pudo descifrar la orden del administrador", e);
        }
    }

    @Transactional
    public AdminStatus enqueue(String bearer, AdminRequest input) {
        long actor = requireSA(bearer);
        if (input == null || input.deviceId() < 1 || input.pin() == null ||
            !input.pin().matches("[0-9]{6,9}") || input.name() == null ||
            !input.name().matches("[A-Za-z][A-Za-z0-9 ._-]{0,16}") ||
            input.password() == null || !input.password().matches("[0-9]{8}"))
            throw new ResponseStatusException(HttpStatus.BAD_REQUEST,
                "PIN: 6-9 cifras, nombre simple y contraseña numérica de 8 cifras");
        // Reject any collision with actual workers, including workers with DNI but no device link.
        Integer device = jdbc.queryForObject(
            "SELECT count(*) FROM intra.biometric_devices WHERE id=? AND active",
            Integer.class, input.deviceId());
        if (device == null || device != 1)
            throw new ResponseStatusException(HttpStatus.NOT_FOUND, "Biométrico no disponible");
        Integer linked = jdbc.queryForObject("""
            SELECT count(*) FROM intra.attendance_employee_device_ids
            WHERE device_id=? AND device_user_id=?
            """, Integer.class, input.deviceId(), input.pin());
        Integer employeeDni = jdbc.queryForObject("""
            SELECT count(*) FROM intra.attendance_employees
            WHERE dni=?
            """, Integer.class, input.pin());
        Integer workerQueue = jdbc.queryForObject("""
            SELECT count(*) FROM intra.biometric_user_provision_commands
            WHERE device_id=? AND device_user_id=? AND status IN ('pending','sent')
            """, Integer.class,input.deviceId(),input.pin());
        if ((linked != null && linked > 0) || (employeeDni != null && employeeDni > 0) ||
            (workerQueue != null && workerQueue > 0))
            throw new ResponseStatusException(HttpStatus.CONFLICT,
                "PIN ocupado por un trabajador; el administrador necesita un PIN exclusivo");
        String ciphertext = encrypt(input.password());
        List<Long> admins = jdbc.query("""
            SELECT id FROM intra.biometric_device_admins WHERE device_id=? AND pin=? FOR UPDATE
            """, (rs,i)->rs.getLong(1),input.deviceId(),input.pin());
        long adminId;
        if (admins.isEmpty()) {
            adminId = jdbc.queryForObject("""
                INSERT INTO intra.biometric_device_admins
                  (device_id,pin,display_name,password_ciphertext,actor_id)
                VALUES(?,?,?,?,?) RETURNING id
                """,Long.class,input.deviceId(),input.pin(),input.name(),ciphertext,actor);
        } else {
            // Keep the last confirmed credential until the device acknowledges the replacement.
            adminId=admins.get(0);
        }
        Integer queued = jdbc.queryForObject("""
            SELECT count(*) FROM intra.biometric_device_admin_commands
            WHERE admin_id=? AND status IN ('pending','sent')
            """,Integer.class,adminId);
        if (queued != null && queued > 0)
            throw new ResponseStatusException(HttpStatus.CONFLICT,
                "Ya existe una orden pendiente para este administrador");
        jdbc.update("""
            INSERT INTO intra.biometric_device_admin_commands
              (admin_id,device_id,pin,display_name,password_ciphertext,actor_id)
            VALUES(?,?,?,?,?,?)
            """,adminId,input.deviceId(),input.pin(),input.name(),ciphertext,actor);
        return latest(bearer, input.deviceId(), input.pin());
    }

    public List<AdminStatus> list(String bearer, long deviceId) {
        requireSA(bearer);
        return jdbc.query("""
            SELECT a.device_id,a.pin,a.display_name,
                COALESCE(q.status,'idle') status,q.result_code,
                COALESCE(q.created_at,a.updated_at)::text updated_at
            FROM intra.biometric_device_admins a
            LEFT JOIN LATERAL (
              SELECT status,result_code,created_at FROM intra.biometric_device_admin_commands
              WHERE admin_id=a.id ORDER BY id DESC LIMIT 1
            ) q ON true WHERE a.device_id=? ORDER BY a.id
            """,(rs,i)->new AdminStatus(rs.getLong(1),rs.getString(2),rs.getString(3),
                rs.getString(4),rs.getObject(5,Integer.class),rs.getString(6)),deviceId);
    }

    public AdminStatus latest(String bearer, long deviceId, String pin) {
        return list(bearer,deviceId).stream().filter(a->a.pin().equals(pin))
            .findFirst().orElseThrow(()->new ResponseStatusException(HttpStatus.NOT_FOUND));
    }

    @Transactional
    public String nextCommand(long deviceId) {
        jdbc.update("""
            UPDATE intra.biometric_device_admin_commands SET status='expired',completed_at=now()
            WHERE device_id=? AND status IN ('pending','sent')
            AND created_at < now() - interval '24 hours'
            """,deviceId);
        List<Outbound> rows = jdbc.query("""
            UPDATE intra.biometric_device_admin_commands q
            SET status='sent',sent_at=now()
            WHERE id=(
                SELECT id FROM intra.biometric_device_admin_commands
                WHERE device_id=? AND
                   (status='pending' OR (status='sent' AND sent_at < now()-interval '60 seconds'))
                ORDER BY id LIMIT 1 FOR UPDATE SKIP LOCKED
            )
            RETURNING q.id,q.pin,q.display_name,q.password_ciphertext
            """,(rs,i)->new Outbound(rs.getLong(1),rs.getString(2),rs.getString(3),rs.getString(4)),deviceId);
        if(rows.isEmpty())return null;
        var row=rows.get(0);
        return "C:" + (OFFSET+row.id()) + ":DATA UPDATE USERINFO PIN=" + row.pin() +
            "\tName=" + row.name() + "\tPri=14\tPasswd=" + decrypt(row.encrypted()) + "\n";
    }

    @Transactional
    public boolean complete(long deviceId,long commandId,int result) {
        if(commandId<OFFSET || commandId>=3_000_000_000L)return false;
        long id=commandId-OFFSET;
        List<Object[]> sent=jdbc.query("""
            SELECT admin_id,display_name,password_ciphertext,actor_id
            FROM intra.biometric_device_admin_commands
            WHERE id=? AND device_id=? AND status='sent' FOR UPDATE
            """,(rs,i)->new Object[]{rs.getLong(1),rs.getString(2),rs.getString(3),rs.getLong(4)},id,deviceId);
        if(sent.isEmpty()) return true;
        if(result==0){
            Object[] request=sent.get(0);
            jdbc.update("""
                UPDATE intra.biometric_device_admins
                SET display_name=?,password_ciphertext=?,actor_id=?,updated_at=now() WHERE id=?
                """,request[1],request[2],request[3],request[0]);
        }
        jdbc.update("""
            UPDATE intra.biometric_device_admin_commands
            SET status=?,result_code=?,completed_at=now()
            WHERE id=? AND device_id=? AND status='sent'
            """,result==0?"confirmed":"failed",result,id,deviceId);
        return true;
    }

    public boolean isReservedPin(long deviceId,String pin) {
        Integer count=jdbc.queryForObject("""
            SELECT count(*) FROM intra.biometric_device_admins WHERE device_id=? AND pin=?
            """,Integer.class,deviceId,pin);
        return count != null && count>0;
    }
}

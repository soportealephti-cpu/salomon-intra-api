package com.salomon.intranet.auth;

import jakarta.validation.Valid;
import jakarta.validation.constraints.NotBlank;
import jakarta.validation.constraints.Size;
import org.springframework.http.HttpStatus;
import org.springframework.http.ResponseEntity;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.security.crypto.bcrypt.BCryptPasswordEncoder;
import org.springframework.web.bind.annotation.*;
import org.springframework.web.server.ResponseStatusException;

import java.nio.charset.StandardCharsets;
import java.security.MessageDigest;
import java.security.SecureRandom;
import java.time.Instant;
import java.util.Base64;
import java.util.HexFormat;
import java.util.List;
import java.util.Locale;
import java.util.Map;

@RestController
@RequestMapping("/api/v1/auth")
public class StaffAuthController {
    private final JdbcTemplate jdbc;
    private final BCryptPasswordEncoder encoder = new BCryptPasswordEncoder();
    private final SecureRandom random = new SecureRandom();
    private static final String INVALID = "Usuario o contraseña incorrectos";

    private final StaffJwt jwt;
    public StaffAuthController(JdbcTemplate jdbc) { this.jdbc = jdbc; this.jwt = new StaffJwt(jdbc); }

    public record Credentials(@NotBlank @Size(max = 100) String username,
                              @NotBlank String password) {}
    public record StaffUser(long id, String username, String name, String role) {}
    public record LoginResult(String token, StaffUser user, String refreshToken, Integer expiresIn) {}
    public record RefreshInput(String refreshToken) {}
    private record StoredUser(long id, String username, String name, String role,
                              String hash, boolean active, boolean locked) {}

    @PostMapping("/login")
    public LoginResult login(@Valid @RequestBody Credentials input,
                             @RequestHeader(value="X-Session-Format",required=false) String format) {
        String username = input.username().trim().toLowerCase(Locale.ROOT);
        List<StoredUser> matches = jdbc.query("""
            SELECT id, username, display_name, role, password_hash, active,
                   (locked_until IS NOT NULL AND locked_until > now()) AS locked
            FROM intra.staff_users WHERE username = ?
            """, (rs, row) -> new StoredUser(rs.getLong("id"),
                rs.getString("username"), rs.getString("display_name"),
                rs.getString("role"), rs.getString("password_hash"),
                rs.getBoolean("active"), rs.getBoolean("locked")), username);
        if (matches.isEmpty()) {
            throw new ResponseStatusException(HttpStatus.UNAUTHORIZED, INVALID);
        }
        StoredUser user = matches.get(0);
        if (!user.active() || user.locked() || !encoder.matches(input.password(), user.hash())) {
            if (user.active() && !user.locked()) {
                jdbc.update("""
                    UPDATE intra.staff_users SET failed_attempts = failed_attempts + 1,
                    locked_until = CASE WHEN failed_attempts + 1 >= 5
                        THEN now() + interval '15 minutes' ELSE NULL END
                    WHERE id = ?
                    """, user.id());
            }
            throw new ResponseStatusException(HttpStatus.UNAUTHORIZED, INVALID);
        }

        jdbc.update("UPDATE intra.staff_users SET failed_attempts = 0, locked_until = NULL WHERE id = ?", user.id());
        byte[] bytes = new byte[32];
        random.nextBytes(bytes);
        String token = Base64.getUrlEncoder().withoutPadding().encodeToString(bytes);
        boolean modern="jwt".equals(format);
        var staff=new StaffUser(user.id(),user.username(),user.name(),user.role());
        String sid=hash(token);
        String access=modern?jwt.issue(sid,staff):token;
        jdbc.update("INSERT INTO intra.staff_sessions (token_hash, user_id, expires_at, auth_version) VALUES (?, ?, now() + interval '24 hours', ?)",
                    sid,user.id(),modern?2:1);
        return new LoginResult(access,staff,modern?token:null,modern?StaffJwt.ACCESS_SECONDS:null);
    }

    @GetMapping("/me")
    public StaffUser me(@RequestHeader(value = "Authorization", required = false) String authorization) {
        var identity=identity(authorization);
        List<StaffUser> found = jdbc.query("""
            SELECT u.id, u.username, u.display_name, u.role
            FROM intra.staff_sessions s
            JOIN intra.staff_users u ON u.id = s.user_id
            WHERE s.token_hash = ? AND s.auth_version = ? AND s.expires_at > now() AND s.created_at + interval '7 days' > now() AND u.active
            """, (rs, row) -> new StaffUser(rs.getLong(1), rs.getString(2),
                rs.getString(3), rs.getString(4)), identity.sid(),identity.version());
        if (found.isEmpty() || (identity.version()==2 && found.getFirst().id()!=identity.userId())) throw new ResponseStatusException(HttpStatus.UNAUTHORIZED, "Sesión no válida");
        return found.get(0);
    }

    /**
     * Renew an existing, unexpired opaque session without issuing another credential.
     * Called only through the authenticated frontend. Never revive revoked or expired sessions.
     * A five-minute write threshold avoids rewriting the session on every attendance poll.
     */
    @PostMapping("/renew")
    public StaffUser renew(@RequestHeader(value = "Authorization", required = false) String authorization) {
        String tokenHash = hash(bearer(authorization));
        jdbc.update("""
            UPDATE intra.staff_sessions s
            SET expires_at = LEAST(now() + interval '24 hours', s.created_at + interval '7 days')
            WHERE s.token_hash = ? AND s.auth_version=1 AND s.expires_at > now()
              AND s.created_at + interval '7 days' > now()
              AND s.expires_at < LEAST(now() + interval '23 hours 55 minutes', s.created_at + interval '7 days')
              AND EXISTS (SELECT 1 FROM intra.staff_users u WHERE u.id = s.user_id AND u.active)
            """, tokenHash);
        return me(authorization);
    }

    @PostMapping("/logout")
    public ResponseEntity<Void> logout(@RequestHeader(value = "Authorization", required = false) String authorization) {
        var identity=identity(authorization);
        jdbc.update("DELETE FROM intra.staff_sessions WHERE token_hash = ? AND auth_version=?", identity.sid(),identity.version());
        return ResponseEntity.noContent().build();
    }


    public record PasswordChange(@NotBlank String currentPassword,
                                 @NotBlank @Size(min = 6, max = 128) String newPassword) {}

    @PostMapping("/change-password")
    public ResponseEntity<Void> changePassword(
        @RequestHeader(value = "Authorization", required = false) String authorization,
        @Valid @RequestBody PasswordChange input) {
        StaffUser current = me(authorization);
        List<String> hashes = jdbc.query(
            "SELECT password_hash FROM intra.staff_users WHERE id = ? AND active = true",
            (rs, row) -> rs.getString(1), current.id());
        if (hashes.isEmpty() || !encoder.matches(input.currentPassword(), hashes.get(0))) {
            throw new ResponseStatusException(HttpStatus.UNAUTHORIZED, "Contraseña actual incorrecta");
        }
        if (input.currentPassword().equals(input.newPassword())) {
            throw new ResponseStatusException(HttpStatus.BAD_REQUEST, "La nueva contraseña debe ser diferente");
        }
        jdbc.update("UPDATE intra.staff_users SET password_hash = ?, failed_attempts = 0, locked_until = NULL WHERE id = ?",
                    encoder.encode(input.newPassword()), current.id());
        jdbc.update("DELETE FROM intra.staff_sessions WHERE user_id = ? AND token_hash <> ?",
                    current.id(), identity(authorization).sid());
        return ResponseEntity.noContent().build();
    }

    @PostMapping("/refresh")
    public LoginResult refresh(@RequestBody RefreshInput input){
        String sid=refreshHash(input);
        var found=jdbc.query("""
            UPDATE intra.staff_sessions s
            SET expires_at=LEAST(now()+interval '24 hours',s.created_at+interval '7 days')
            FROM intra.staff_users u
            WHERE s.token_hash=? AND s.auth_version=2 AND s.expires_at>now()
              AND s.created_at+interval '7 days'>now() AND u.id=s.user_id AND u.active
            RETURNING u.id,u.username,u.display_name,u.role
            """,(rs,i)->new StaffUser(rs.getLong(1),rs.getString(2),rs.getString(3),rs.getString(4)),sid);
        if(found.isEmpty())throw new ResponseStatusException(HttpStatus.UNAUTHORIZED,"Sesión vencida");
        var user=found.getFirst();
        return new LoginResult(jwt.issue(sid,user),user,null,StaffJwt.ACCESS_SECONDS);
    }

    @PostMapping("/logout-refresh")
    public ResponseEntity<Void> logoutRefresh(@RequestBody RefreshInput input){
        jdbc.update("DELETE FROM intra.staff_sessions WHERE token_hash=? AND auth_version=2",refreshHash(input));
        return ResponseEntity.noContent().build();
    }

    @DeleteMapping("/sessions/{userId}")
    public Map<String,Object> revokeSessions(@RequestHeader(value="Authorization",required=false) String authorization,@PathVariable long userId){
        if(!"SA".equals(me(authorization).role()))throw new ResponseStatusException(HttpStatus.FORBIDDEN);
        int revoked=jdbc.update("DELETE FROM intra.staff_sessions WHERE user_id=?",userId);
        return Map.of("revoked",revoked);
    }

    @GetMapping("/jwks")
    public Map<String,Object> jwks(){return jwt.jwks();}

    private static String refreshHash(RefreshInput input){
        if(input==null||input.refreshToken()==null||!input.refreshToken().matches("[A-Za-z0-9_-]{43}"))
            throw new ResponseStatusException(HttpStatus.UNAUTHORIZED,"Sesión no válida");
        return hash(input.refreshToken());
    }
    private record Identity(String sid,int version,long userId){}
    private Identity identity(String authorization){
        if(authorization==null||!authorization.startsWith("Bearer ")||authorization.length()>4096)
            throw new ResponseStatusException(HttpStatus.UNAUTHORIZED,"Sesión no válida");
        String token=authorization.substring(7);
        if(token.matches("[A-Za-z0-9_-]{43}"))return new Identity(hash(token),1,0);
        var claims=jwt.verify(token);
        return new Identity(claims.sid(),2,claims.userId());
    }

    private static String bearer(String authorization) {
        if (authorization == null || !authorization.startsWith("Bearer ") || authorization.length() > 300) {
            throw new ResponseStatusException(HttpStatus.UNAUTHORIZED, "Sesión no válida");
        }
        String token = authorization.substring(7);
        if (!token.matches("[A-Za-z0-9_-]{43}")) {
            throw new ResponseStatusException(HttpStatus.UNAUTHORIZED, "Sesión no válida");
        }
        return token;
    }

    private static String hash(String token) {
        try {
            byte[] digest = MessageDigest.getInstance("SHA-256")
                .digest(token.getBytes(StandardCharsets.US_ASCII));
            return HexFormat.of().formatHex(digest);
        } catch (java.security.NoSuchAlgorithmException e) {
            throw new IllegalStateException(e);
        }
    }
}

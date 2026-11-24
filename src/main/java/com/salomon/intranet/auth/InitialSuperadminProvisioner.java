package com.salomon.intranet.auth;

import org.springframework.beans.factory.annotation.Value;
import org.springframework.boot.ApplicationArguments;
import org.springframework.boot.ApplicationRunner;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.security.crypto.bcrypt.BCryptPasswordEncoder;
import org.springframework.stereotype.Component;

/**
 * Aprovisiona la primera cuenta SA únicamente al configurar el secreto de
 * arranque en el servidor y únicamente si aún no existe. Nunca la restablece.
 */
@Component
public class InitialSuperadminProvisioner implements ApplicationRunner {
    private final JdbcTemplate jdbc;
    private final String initialPassword;
    private final BCryptPasswordEncoder encoder = new BCryptPasswordEncoder(12);

    public InitialSuperadminProvisioner(JdbcTemplate jdbc,
        @Value("${INTRA_SA_INITIAL_PASSWORD:}") String initialPassword) {
        this.jdbc = jdbc;
        this.initialPassword = initialPassword;
    }

    @Override
    public void run(ApplicationArguments args) {
        if (initialPassword.isBlank()) return;
        if (initialPassword.length() < 9) {
            throw new IllegalStateException("INTRA_SA_INITIAL_PASSWORD debe tener al menos 9 caracteres");
        }
        jdbc.update("""
            INSERT INTO intra.staff_users (username, display_name, password_hash, role, active)
            VALUES ('sa', 'Superadministrador', ?, 'SA', true)
            ON CONFLICT (username) DO NOTHING
            """, encoder.encode(initialPassword));
    }
}

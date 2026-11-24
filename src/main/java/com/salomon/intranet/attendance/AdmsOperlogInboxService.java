package com.salomon.intranet.attendance;

import org.springframework.http.HttpStatus;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;
import org.springframework.web.server.ResponseStatusException;

import java.util.List;

/**
 * Quarantines unknown OPERLOG payloads transactionally before acknowledging.
 * Do not parse these as attendance or user changes: firmware operation records
 * may include formats not understood by this application.
 */
@Service
public class AdmsOperlogInboxService {
    private final JdbcTemplate jdbc;
    private static final int MAX_BODY_BYTES = 65536;

    public AdmsOperlogInboxService(JdbcTemplate jdbc) {
        this.jdbc = jdbc;
    }

    @Transactional
    public String receive(String serial, byte[] body) {
        if (body == null || body.length == 0 || body.length > MAX_BODY_BYTES) {
            throw new ResponseStatusException(HttpStatus.SERVICE_UNAVAILABLE,
                "OPERLOG vacío o demasiado grande; se mantiene pendiente de reenvío");
        }
        List<Long> ids = jdbc.query("""
            SELECT id FROM intra.biometric_devices
            WHERE serial_number = ? AND active = true
            """, ps -> ps.setString(1, serial), (rs, n) -> rs.getLong(1));
        if (ids.isEmpty()) {
            throw new ResponseStatusException(HttpStatus.FORBIDDEN, "Biométrico no registrado");
        }
        jdbc.update("""
            INSERT INTO intra.biometric_operlog_inbox (device_id, payload)
            VALUES (?, ?)
            ON CONFLICT (device_id, payload_sha256) DO NOTHING
            """, ids.get(0), body);
        // Only ACK after successful persistence, including idempotent retries.
        return "OK\n";
    }
}

package com.salomon.intranet.attendance;

import org.springframework.http.HttpStatus;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;
import org.springframework.web.server.ResponseStatusException;

import java.nio.ByteBuffer;
import java.nio.charset.CharacterCodingException;
import java.nio.charset.CodingErrorAction;
import java.nio.charset.StandardCharsets;
import java.time.DateTimeException;
import java.time.LocalDateTime;
import java.time.ZoneId;
import java.time.format.DateTimeFormatter;
import java.time.format.DateTimeParseException;
import java.util.ArrayList;
import java.util.List;
import java.util.regex.Pattern;

/**
 * Stores ADMS ATTLOG punches and acknowledges only wholly validated,
 * transactionally persisted batches. Repeated uploads are idempotent.
 */
@Service
public class AdmsAttendanceService {
    private static final ZoneId DEVICE_ZONE = ZoneId.of("America/Lima");
    private static final DateTimeFormatter[] TIMESTAMP_FORMATS = {
        DateTimeFormatter.ofPattern("yyyy-MM-dd HH:mm:ss"),
        DateTimeFormatter.ofPattern("yyyy-MM-dd'T'HH:mm:ss"),
        DateTimeFormatter.ofPattern("yyyy-MM-dd HH:mm:ss.SSS"),
        DateTimeFormatter.ofPattern("yyyy-MM-dd'T'HH:mm:ss.SSS")
    };
    private static final Pattern PIN = Pattern.compile("[A-Za-z0-9._-]{1,64}");
    private static final Pattern META = Pattern.compile("[A-Za-z0-9._-]{0,32}");
    private static final Pattern WORK_CODE = Pattern.compile("[A-Za-z0-9._-]{0,64}");
    private static final int MAX_BODY_BYTES = 262144;
    private static final int MAX_ROWS = 2000;

    private final JdbcTemplate jdbc;

    public AdmsAttendanceService(JdbcTemplate jdbc) { this.jdbc = jdbc; }

    record Punch(String pin, LocalDateTime localTime, String status, String verify, String workCode) {}

    static List<Punch> parse(byte[] body) {
        if (body == null || body.length == 0 || body.length > MAX_BODY_BYTES) {
            throw invalid("Paquete ATTLOG vacío o demasiado grande");
        }
        final String text;
        try {
            text = StandardCharsets.UTF_8.newDecoder()
                .onMalformedInput(CodingErrorAction.REPORT)
                .onUnmappableCharacter(CodingErrorAction.REPORT)
                .decode(ByteBuffer.wrap(body)).toString();
        } catch (CharacterCodingException e) {
            throw invalid("ATTLOG no está codificado en UTF-8");
        }
        List<Punch> result = new ArrayList<>();
        for (String line : text.split("\\r?\\n", -1)) {
            String entry = line.strip();
            if (entry.isEmpty()) continue;
            // Some firmwares prefix attendance lines with "ATTLOG ".
            if (entry.regionMatches(true, 0, "ATTLOG ", 0, 7)) {
                entry = entry.substring(7);
            }
            String[] fields = entry.split("\\t", -1);
            if (fields.length < 2) {
                throw invalid("Formato ATTLOG desconocido; se esperaban columnas separadas por tabulación");
            }
            String pin = fields[0].trim();
            if (!PIN.matcher(pin).matches()) throw invalid("PIN inválido en ATTLOG");

            LocalDateTime localTime = null;
            for (DateTimeFormatter format : TIMESTAMP_FORMATS) {
                try { localTime = LocalDateTime.parse(fields[1].trim(), format); break; }
                catch (DateTimeParseException ignored) { }
            }
            if (localTime == null) throw invalid("Fecha u hora ATTLOG no reconocida");

            String status = fields.length > 2 ? fields[2].trim() : "";
            String verify = fields.length > 3 ? fields[3].trim() : "";
            String workCode = fields.length > 4 ? fields[4].trim() : "";
            if (!META.matcher(status).matches() || !META.matcher(verify).matches() ||
                !WORK_CODE.matcher(workCode).matches()) {
                throw invalid("Campos ATTLOG inválidos");
            }
            result.add(new Punch(pin, localTime, status, verify, workCode));
            if (result.size() > MAX_ROWS) throw invalid("Paquete ATTLOG supera el límite de eventos");
        }
        if (result.isEmpty()) throw invalid("Paquete ATTLOG sin marcaciones");
        return result;
    }

    private static ResponseStatusException invalid(String message) {
        // Do not send a success acknowledgement: the device should retain/retry.
        return new ResponseStatusException(HttpStatus.SERVICE_UNAVAILABLE, message);
    }

    @Transactional
    public String receive(String serial, byte[] body) {
        List<Punch> punches = parse(body); // Validate entire batch before inserting any row.
        List<Long> devices = jdbc.query("""
            SELECT id FROM intra.biometric_devices WHERE serial_number = ? AND active = true
            """, ps -> ps.setString(1, serial), (rs, i) -> rs.getLong(1));
        if (devices.isEmpty()) {
            throw new ResponseStatusException(HttpStatus.FORBIDDEN, "Dispositivo no registrado");
        }
        long deviceId = devices.get(0);
        for (Punch punch : punches) {
            jdbc.update("""
                INSERT INTO intra.biometric_attendance_events
                  (device_id, device_user_id, device_punched_at, punched_at,
                   attendance_status, verify_mode, work_code)
                VALUES (?, ?, ?, ?, ?, ?, ?)
                ON CONFLICT ON CONSTRAINT biometric_attendance_event_unique DO NOTHING
                """, deviceId, punch.pin(), punch.localTime(),
                punch.localTime().atZone(DEVICE_ZONE).toOffsetDateTime(),
                punch.status(), punch.verify(), punch.workCode());
        }
        // Report records accepted (including previously saved duplicates), not
        // just newly inserted ones. This prevents indefinite device retries.
        return "OK: " + punches.size() + "\n";
    }
}

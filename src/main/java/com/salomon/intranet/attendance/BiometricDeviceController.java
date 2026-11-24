package com.salomon.intranet.attendance;

import com.salomon.intranet.auth.StaffAuthController;
import org.springframework.http.HttpStatus;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.web.bind.annotation.*;
import org.springframework.web.server.ResponseStatusException;

import java.time.Instant;
import java.time.OffsetDateTime;
import java.util.List;

@RestController
@RequestMapping("/api/v1/biometrics")
public class BiometricDeviceController {
    private final JdbcTemplate jdbc;
    private final StaffAuthController auth;

    public BiometricDeviceController(JdbcTemplate jdbc, StaffAuthController auth) {
        this.jdbc = jdbc;
        this.auth = auth;
    }

    public record Device(long id, String serialNumber, String name, String model,
                         String location, boolean active, Instant lastSeenAt,
                         String lastEndpoint, long contactCount) {}
    public record CreateDevice(String serialNumber, String name, String model, String location) {}
    public record UpdateDevice(Boolean active) {}
    public record EditDevice(String name, String model, String location) {}

    private void requireSA(String authorization) {
        if (!"SA".equals(auth.me(authorization).role())) {
            throw new ResponseStatusException(HttpStatus.FORBIDDEN, "Acceso restringido");
        }
    }

    @GetMapping
    public List<Device> list(@RequestHeader(value = "Authorization", required = false) String authorization) {
        requireSA(authorization);
        return jdbc.query("""
            SELECT d.id, d.serial_number, d.name, d.model, d.location, d.active,
                   c.last_seen_at, c.last_endpoint, COALESCE(c.contact_count, 0) contact_count
            FROM intra.biometric_devices d
            LEFT JOIN intra.biometric_device_contacts c ON c.serial_number = d.serial_number
            ORDER BY d.id DESC
            """, (rs, i) -> new Device(rs.getLong("id"), rs.getString("serial_number"),
                rs.getString("name"), rs.getString("model"), rs.getString("location"),
                rs.getBoolean("active"),
                rs.getObject("last_seen_at") == null ? null :
                    rs.getObject("last_seen_at", OffsetDateTime.class).toInstant(),
                rs.getString("last_endpoint"), rs.getLong("contact_count")));
    }

    @PostMapping
    @ResponseStatus(HttpStatus.CREATED)
    public Device create(@RequestHeader(value = "Authorization", required = false) String authorization,
                         @RequestBody CreateDevice request) {
        requireSA(authorization);
        if (request == null || request.serialNumber() == null ||
            !request.serialNumber().trim().matches("[A-Za-z0-9_-]{4,64}") ||
            request.name() == null || request.name().isBlank() || request.name().length() > 120 ||
            request.model() == null || request.model().isBlank() || request.model().length() > 100 ||
            (request.location() != null && request.location().length() > 160)) {
            throw new ResponseStatusException(HttpStatus.BAD_REQUEST, "Datos del equipo inválidos");
        }
        String sn = request.serialNumber().trim();
        int count = jdbc.update("""
            INSERT INTO intra.biometric_devices(serial_number, name, model, location)
            VALUES (?, ?, ?, ?)
            ON CONFLICT (serial_number) DO NOTHING
            """, sn, request.name().trim(), request.model().trim(),
            request.location() == null ? null : request.location().trim());
        if (count == 0) throw new ResponseStatusException(HttpStatus.CONFLICT, "Número de serie ya registrado");
        return list(authorization).stream().filter(d -> d.serialNumber().equals(sn))
            .findFirst().orElseThrow();
    }

    @PatchMapping("/{id}")
    public Device setActive(@RequestHeader(value = "Authorization", required = false) String authorization,
                            @PathVariable long id, @RequestBody UpdateDevice request) {
        requireSA(authorization);
        if (request == null || request.active() == null) {
            throw new ResponseStatusException(HttpStatus.BAD_REQUEST, "Estado requerido");
        }
        int count = jdbc.update("""
            UPDATE intra.biometric_devices SET active = ?, updated_at = now() WHERE id = ?
            """, request.active(), id);
        if (count == 0) throw new ResponseStatusException(HttpStatus.NOT_FOUND, "Equipo no encontrado");
        return list(authorization).stream().filter(d -> d.id() == id).findFirst().orElseThrow();
    }
    @PutMapping("/{id}")
    public Device edit(@RequestHeader(value = "Authorization", required = false) String authorization,
                       @PathVariable long id, @RequestBody EditDevice request) {
        requireSA(authorization);
        if (request == null || request.name() == null || request.name().isBlank() ||
            request.name().length() > 120 || request.model() == null ||
            request.model().isBlank() || request.model().length() > 100 ||
            (request.location() != null && request.location().length() > 160)) {
            throw new ResponseStatusException(HttpStatus.BAD_REQUEST, "Datos del equipo inválidos");
        }
        int changed = jdbc.update("""
            UPDATE intra.biometric_devices
            SET name = ?, model = ?, location = ?, updated_at = now()
            WHERE id = ?
            """, request.name().trim(), request.model().trim(),
            request.location() == null ? null : request.location().trim(), id);
        if (changed == 0) throw new ResponseStatusException(HttpStatus.NOT_FOUND, "Equipo no encontrado");
        return list(authorization).stream().filter(d -> d.id() == id).findFirst().orElseThrow();
    }

    @DeleteMapping("/{id}")
    @ResponseStatus(HttpStatus.NO_CONTENT)
    public void delete(@RequestHeader(value = "Authorization", required = false) String authorization,
                       @PathVariable long id) {
        requireSA(authorization);
        int changed = jdbc.update("DELETE FROM intra.biometric_devices WHERE id = ?", id);
        if (changed == 0) throw new ResponseStatusException(HttpStatus.NOT_FOUND, "Equipo no encontrado");
    }

}

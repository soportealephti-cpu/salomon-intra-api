package com.salomon.intranet.attendance;

import org.springframework.http.HttpStatus;
import org.springframework.web.bind.annotation.*;
import java.util.List;

@RestController
@RequestMapping("/api/v1/attendance/device-admins")
public class BiometricDeviceAdminController {
    private final AdmsDeviceAdminService service;
    public BiometricDeviceAdminController(AdmsDeviceAdminService service) {
        this.service = service;
    }

    @GetMapping
    public List<AdmsDeviceAdminService.AdminStatus> list(
        @RequestHeader(value="Authorization",required=false) String authorization,
        @RequestParam long deviceId) {
        return service.list(authorization,deviceId);
    }

    @PostMapping
    @ResponseStatus(HttpStatus.ACCEPTED)
    public AdmsDeviceAdminService.AdminStatus enqueue(
        @RequestHeader(value="Authorization",required=false) String authorization,
        @RequestBody AdmsDeviceAdminService.AdminRequest input) {
        return service.enqueue(authorization,input);
    }
}

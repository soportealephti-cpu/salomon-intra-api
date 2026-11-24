package com.salomon.intranet.attendance;

import org.springframework.http.HttpStatus;
import org.springframework.web.bind.annotation.*;

@RestController
@RequestMapping("/api/v1/attendance/provision")
public class BiometricUserProvisionController {
    private final AdmsUserProvisionService service;

    public BiometricUserProvisionController(AdmsUserProvisionService service) {
        this.service = service;
    }

    @PostMapping("/{employeeId}")
    @ResponseStatus(HttpStatus.ACCEPTED)
    public AdmsUserProvisionService.ProvisionStatus enqueue(
        @RequestHeader(value="Authorization",required=false) String authorization,
        @PathVariable long employeeId,
        @RequestBody AdmsUserProvisionService.ProvisionRequest request) {
        return service.enqueue(authorization, employeeId, request);
    }

    @GetMapping("/{employeeId}")
    public AdmsUserProvisionService.ProvisionStatus status(
        @RequestHeader(value="Authorization",required=false) String authorization,
        @PathVariable long employeeId, @RequestParam long deviceId) {
        return service.latest(authorization, employeeId, deviceId);
    }
    @PostMapping("/bulk")
    @ResponseStatus(HttpStatus.ACCEPTED)
    public AdmsUserProvisionService.BulkProvisionResult enqueueBulk(
        @RequestHeader(value="Authorization",required=false) String authorization,
        @RequestBody AdmsUserProvisionService.BulkProvisionRequest request) {
        return service.enqueueBulk(authorization,request);
    }

    @GetMapping("/bulk")
    public AdmsUserProvisionService.BulkProvisionResult bulkStatus(
        @RequestHeader(value="Authorization",required=false) String authorization,
        @RequestParam long deviceId) {
        return service.bulkStatus(authorization,deviceId);
    }

}

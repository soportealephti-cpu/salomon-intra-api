package com.salomon.intranet.status;

import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RestController;

@RestController
@RequestMapping("/api/v1/status")
public class ApiStatusController {

    @GetMapping
    public ApiStatusResponse getStatus() {
        return new ApiStatusResponse("UP", "salomon-intra-api");
    }

    public record ApiStatusResponse(String status, String service) {
    }
}

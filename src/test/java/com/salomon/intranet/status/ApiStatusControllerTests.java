package com.salomon.intranet.status;

import org.junit.jupiter.api.Test;

import static org.junit.jupiter.api.Assertions.assertEquals;

class ApiStatusControllerTests {

    private final ApiStatusController controller = new ApiStatusController();

    @Test
    void returnsExpectedContract() {
        var status = controller.getStatus();

        assertEquals("UP", status.status());
        assertEquals("salomon-intra-api", status.service());
    }
}

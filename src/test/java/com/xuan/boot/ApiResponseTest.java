package com.xuan.boot;

import com.xuan.boot.dto.ApiResponse;
import org.junit.jupiter.api.Assertions;
import org.junit.jupiter.api.Test;

class ApiResponseTest {
    @Test
    void okShouldWrapData() {
        ApiResponse<String> response = ApiResponse.ok("created", "data");
        Assertions.assertTrue(response.isSuccess());
        Assertions.assertEquals("created", response.getMessage());
        Assertions.assertEquals("data", response.getData());
    }

    @Test
    void failShouldCarryMessage() {
        ApiResponse<Void> response = ApiResponse.fail("bad request");
        Assertions.assertFalse(response.isSuccess());
        Assertions.assertEquals("bad request", response.getMessage());
    }
}

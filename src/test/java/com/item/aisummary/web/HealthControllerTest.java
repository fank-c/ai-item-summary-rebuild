package com.item.aisummary.web;

import org.junit.jupiter.api.Test;

import static org.assertj.core.api.Assertions.assertThat;

class HealthControllerTest {

    @Test
    void reportsApplicationAsUp() {
        HealthController.HealthResponse response = new HealthController().health();

        assertThat(response.status()).isEqualTo("UP");
    }
}

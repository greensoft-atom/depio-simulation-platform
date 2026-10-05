package com.backend.platform;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

import java.util.Map;

import com.backend.common.RefusedConfiguration;

import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

/** The payment provider's switch (D-68): off unless the operator names the simulated one. */
class PaymentServiceTest {

    @Test
    @DisplayName("unset or blank, no provider; simulated, the simulated one; any other name stops the start")
    void theProviderIsNamed() {
        assertThat(PaymentService.providerFrom(Map.of())).isNull();
        assertThat(PaymentService.providerFrom(Map.of("BACKEND_PAYMENT_PROVIDER", " "))).isNull();
        assertThat(PaymentService.providerFrom(Map.of("BACKEND_PAYMENT_PROVIDER", "simulated")))
                .isEqualTo(PaymentService.SIMULATED);
        assertThatThrownBy(() -> PaymentService.providerFrom(Map.of("BACKEND_PAYMENT_PROVIDER", "Simulated")))
                .isInstanceOf(RefusedConfiguration.class).hasMessageContaining("simulated");
        assertThatThrownBy(() -> PaymentService.providerFrom(Map.of("BACKEND_PAYMENT_PROVIDER", "a-real-one")))
                .isInstanceOf(RefusedConfiguration.class).hasMessageContaining("BACKEND_PAYMENT_PROVIDER");
    }
}

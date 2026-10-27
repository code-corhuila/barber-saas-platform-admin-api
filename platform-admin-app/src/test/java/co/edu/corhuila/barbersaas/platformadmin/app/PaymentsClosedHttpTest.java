package co.edu.corhuila.barbersaas.platformadmin.app;

import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.post;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.jsonPath;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

import com.fasterxml.jackson.databind.ObjectMapper;
import java.util.UUID;
import org.junit.jupiter.api.Test;
import org.springframework.http.MediaType;
import org.springframework.test.context.TestPropertySource;

/** Neither a Wompi key nor PAYMENTS_SIMULATOR=true (DEC-PLAT-08): no payment starts and the simulator is closed. */
@TestPropertySource(properties = "PAYMENTS_SIMULATOR=false")
class PaymentsClosedHttpTest extends HttpTest {

    private static final ObjectMapper JSON = new ObjectMapper();

    @Test
    void without_a_checkout_paying_answers_503_and_stores_nothing() throws Exception {
        UUID shop = BARBERSHOPS.add("TRIAL", null, 10);
        String plan = JSON.readTree(http.perform(post("/api/v1/platform/plans").header("Authorization", superAdmin())
                        .header("Idempotency-Key", "key-" + UUID.randomUUID()).contentType(MediaType.APPLICATION_JSON)
                        .content("{\"name\":\"Closed " + UUID.randomUUID().toString().substring(0, 6)
                                + "\",\"priceCents\":100,\"maxBarbers\":3}"))
                .andExpect(status().isCreated()).andReturn().getResponse().getContentAsString()).get("id").asText();

        http.perform(post("/api/v1/payments").header("Authorization", bearer("ADMIN_BARBERSHOP", shop))
                        .header("Idempotency-Key", "pay-" + UUID.randomUUID()).contentType(MediaType.APPLICATION_JSON)
                        .content("{\"planId\":\"" + plan + "\"}"))
                .andExpect(status().isServiceUnavailable())
                .andExpect(jsonPath("$.error").value("SERVICE_UNAVAILABLE"));
        http.perform(get("/api/v1/payments").header("Authorization", bearer("ADMIN_BARBERSHOP", shop)))
                .andExpect(jsonPath("$.data.length()").value(0));
    }

    @Test
    void the_simulated_checkout_is_closed() throws Exception {
        http.perform(get("/api/v1/payments/simulator/BS-anything")).andExpect(status().isNotFound());
        http.perform(post("/api/v1/payments/simulator/BS-anything/approve")).andExpect(status().isNotFound());
    }
}

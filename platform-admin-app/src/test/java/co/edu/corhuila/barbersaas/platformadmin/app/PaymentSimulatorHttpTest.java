package co.edu.corhuila.barbersaas.platformadmin.app;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertTrue;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.post;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.content;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.jsonPath;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import java.util.UUID;
import org.hamcrest.Matchers;
import org.junit.jupiter.api.Test;
import org.springframework.http.MediaType;

/** Without Wompi keys a payment goes to the simulated checkout (DEC-PLAT-08), with the real rest of the flow. */
class PaymentSimulatorHttpTest extends HttpTest {

    private static final ObjectMapper JSON = new ObjectMapper();

    private JsonNode start(UUID shop, long cents) throws Exception {
        String plan = JSON.readTree(http.perform(post("/api/v1/platform/plans").header("Authorization", superAdmin())
                        .header("Idempotency-Key", "key-" + UUID.randomUUID()).contentType(MediaType.APPLICATION_JSON)
                        .content("{\"name\":\"Sim " + UUID.randomUUID().toString().substring(0, 6) + "\",\"priceCents\":"
                                + cents + ",\"maxBarbers\":3}"))
                .andExpect(status().isCreated()).andReturn().getResponse().getContentAsString()).get("id").asText();
        return JSON.readTree(http.perform(post("/api/v1/payments").header("Authorization", bearer("ADMIN_BARBERSHOP", shop))
                        .header("Idempotency-Key", "pay-" + UUID.randomUUID()).contentType(MediaType.APPLICATION_JSON)
                        .content("{\"planId\":\"" + plan + "\"}"))
                .andExpect(status().isCreated()).andReturn().getResponse().getContentAsString());
    }

    @Test
    void the_simulated_checkout_approves_a_payment_without_a_token() throws Exception {
        UUID shop = BARBERSHOPS.add("TRIAL", null, 10);
        JsonNode started = start(shop, 9_990_000);
        String url = started.get("checkoutUrl").asText();
        assertTrue(url.startsWith("/api/v1/payments/simulator/BS-"), url);

        http.perform(get(url)).andExpect(status().isOk())
                .andExpect(content().string(Matchers.containsString("Pasarela de prueba (simulada)")))
                .andExpect(content().string(Matchers.containsString("Aprobar pago")));
        http.perform(post(url + "/approve")).andExpect(status().isOk())
                .andExpect(content().string(Matchers.containsString("Pago aprobado")));
        http.perform(get(url)).andExpect(content().string(Matchers.containsString("ya fue procesado")));

        assertEquals("ACTIVE", BARBERSHOPS.row(shop).get("status").asText());
        http.perform(get("/api/v1/payments").header("Authorization", bearer("ADMIN_BARBERSHOP", shop)))
                .andExpect(jsonPath("$.data[0].status").value("APPROVED"));
    }

    @Test
    void a_declined_simulated_payment_changes_only_the_payment() throws Exception {
        UUID shop = BARBERSHOPS.add("TRIAL", null, 10);
        String url = start(shop, 100).get("checkoutUrl").asText();

        http.perform(post(url + "/decline")).andExpect(status().isOk())
                .andExpect(content().string(Matchers.containsString("Pago rechazado")));
        assertEquals("TRIAL", BARBERSHOPS.row(shop).get("status").asText());
        http.perform(get("/api/v1/payments/simulator/BS-unknown")).andExpect(status().isNotFound());
    }
}

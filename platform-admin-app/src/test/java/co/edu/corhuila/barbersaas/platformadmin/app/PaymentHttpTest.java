package co.edu.corhuila.barbersaas.platformadmin.app;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertTrue;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.post;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.jsonPath;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import java.nio.charset.StandardCharsets;
import java.security.MessageDigest;
import java.util.HexFormat;
import java.util.UUID;
import org.junit.jupiter.api.Test;
import org.springframework.http.MediaType;
import org.springframework.test.context.DynamicPropertyRegistry;
import org.springframework.test.context.DynamicPropertySource;

/** Subscription and Wompi payments over HTTP (platform-admin-service.yaml 1.4.0, ADR-017). */
class PaymentHttpTest extends HttpTest {

    private static final ObjectMapper JSON = new ObjectMapper();
    private static final String EVENTS_SECRET = "test_events_secret";

    @DynamicPropertySource
    static void wompi(DynamicPropertyRegistry registry) {
        registry.add("WOMPI_PUBLIC_KEY", () -> "pub_test_abc");
        registry.add("WOMPI_INTEGRITY_SECRET", () -> "test_integrity_secret");
        registry.add("WOMPI_EVENTS_SECRET", () -> EVENTS_SECRET);
    }

    private String plan(String name, long cents, int maxBarbers) throws Exception {
        String body = http.perform(post("/api/v1/platform/plans").header("Authorization", superAdmin())
                        .header("Idempotency-Key", "key-" + UUID.randomUUID()).contentType(MediaType.APPLICATION_JSON)
                        .content("{\"name\":\"" + name + "\",\"priceCents\":" + cents + ",\"maxBarbers\":" + maxBarbers
                                + "}"))
                .andExpect(status().isCreated()).andReturn().getResponse().getContentAsString();
        return JSON.readTree(body).get("id").asText();
    }

    @Test
    void the_owner_pays_a_plan_and_the_signed_event_activates_it() throws Exception {
        String pro = plan("Pro " + UUID.randomUUID().toString().substring(0, 6), 9_990_000, 6);
        UUID shop = BARBERSHOPS.add("TRIAL", null, 10);
        String owner = bearer("ADMIN_BARBERSHOP", shop);

        http.perform(get("/api/v1/subscription").header("Authorization", owner))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.status").value("TRIAL"))
                .andExpect(jsonPath("$.paidUntil").isEmpty());

        String started = http.perform(post("/api/v1/payments").header("Authorization", owner)
                        .header("Idempotency-Key", "pay-" + shop).contentType(MediaType.APPLICATION_JSON)
                        .content("{\"planId\":\"" + pro + "\"}"))
                .andExpect(status().isCreated())
                .andExpect(jsonPath("$.payment.status").value("PENDING"))
                .andExpect(jsonPath("$.payment.amountCents").value(9_990_000))
                .andReturn().getResponse().getContentAsString();
        JsonNode payment = JSON.readTree(started).get("payment");
        String checkout = JSON.readTree(started).get("checkoutUrl").asText();
        assertTrue(checkout.startsWith("https://checkout.wompi.co/p/?public-key=pub_test_abc"));
        assertTrue(checkout.contains("signature%3Aintegrity=") || checkout.contains("signature:integrity="));

        String event = event(payment.get("reference").asText(), "tx-" + shop, "APPROVED", 9_990_000, EVENTS_SECRET);
        http.perform(post("/api/v1/payments/wompi/events").contentType(MediaType.APPLICATION_JSON).content(event))
                .andExpect(status().isOk());
        http.perform(post("/api/v1/payments/wompi/events").contentType(MediaType.APPLICATION_JSON).content(event))
                .andExpect(status().isOk());                                    // repeated: no change

        assertEquals("ACTIVE", BARBERSHOPS.row(shop).get("status").asText());
        assertEquals(pro, BARBERSHOPS.row(shop).get("planId").asText());
        http.perform(get("/api/v1/payments").header("Authorization", owner))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.data[0].status").value("APPROVED"))
                .andExpect(jsonPath("$.data[0].periodEnd").isNotEmpty());
        http.perform(get("/api/v1/subscription").header("Authorization", owner))
                .andExpect(jsonPath("$.paidUntil").isNotEmpty());
    }

    @Test
    void a_forged_event_is_401_and_changes_nothing() throws Exception {
        String plan = plan("Forged " + UUID.randomUUID().toString().substring(0, 6), 100, 2);
        UUID shop = BARBERSHOPS.add("TRIAL", null, 1);
        String started = http.perform(post("/api/v1/payments").header("Authorization", bearer("ADMIN_BARBERSHOP", shop))
                        .header("Idempotency-Key", "pay-" + shop).contentType(MediaType.APPLICATION_JSON)
                        .content("{\"planId\":\"" + plan + "\"}"))
                .andExpect(status().isCreated()).andReturn().getResponse().getContentAsString();
        String reference = JSON.readTree(started).get("payment").get("reference").asText();

        http.perform(post("/api/v1/payments/wompi/events").contentType(MediaType.APPLICATION_JSON)
                        .content(event(reference, "tx-forged", "APPROVED", 100, "not-the-secret")))
                .andExpect(status().isUnauthorized());
        assertEquals("TRIAL", BARBERSHOPS.row(shop).get("status").asText());
    }

    @Test
    void a_plan_smaller_than_the_team_is_422_and_other_roles_are_403() throws Exception {
        String small = plan("Small " + UUID.randomUUID().toString().substring(0, 6), 100, 2);
        UUID shop = BARBERSHOPS.add("TRIAL", null, 1);
        BARBERSHOPS.barbers(shop, 4);

        http.perform(post("/api/v1/payments").header("Authorization", bearer("ADMIN_BARBERSHOP", shop))
                        .header("Idempotency-Key", "pay-" + shop).contentType(MediaType.APPLICATION_JSON)
                        .content("{\"planId\":\"" + small + "\"}"))
                .andExpect(status().isUnprocessableEntity())
                .andExpect(jsonPath("$.message").value("This plan allows 2 barbers and the barbershop has 4"));
        http.perform(get("/api/v1/subscription").header("Authorization", bearer("CLIENT", null)))
                .andExpect(status().isForbidden());
        http.perform(get("/api/v1/payments").header("Authorization", superAdmin())).andExpect(status().isOk());
    }

    /** A transaction.updated event signed as Wompi signs it. */
    private static String event(String reference, String transactionId, String status, long cents, String secret)
            throws Exception {
        long timestamp = 1_761_500_000L;
        String checksum = HexFormat.of().formatHex(MessageDigest.getInstance("SHA-256").digest(
                (transactionId + status + cents + timestamp + secret).getBytes(StandardCharsets.UTF_8)));
        return """
                {"event":"transaction.updated","data":{"transaction":{"id":"%s","reference":"%s","status":"%s",
                "amount_in_cents":%d}},"signature":{"properties":["transaction.id","transaction.status",
                "transaction.amount_in_cents"],"checksum":"%s"},"timestamp":%d,"sent_at":"2026-10-27T15:00:00Z"}
                """.formatted(transactionId, reference, status, cents, checksum, timestamp);
    }
}

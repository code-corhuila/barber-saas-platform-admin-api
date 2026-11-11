package co.edu.corhuila.barbersaas.platformadmin.app;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.post;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.jsonPath;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.sun.net.httpserver.HttpServer;
import java.io.IOException;
import java.net.InetSocketAddress;
import java.nio.charset.StandardCharsets;
import java.util.UUID;
import org.junit.jupiter.api.Test;
import org.springframework.http.MediaType;
import org.springframework.test.context.DynamicPropertyRegistry;
import org.springframework.test.context.DynamicPropertySource;

/** The worker's subscription jobs and the outbox relay over HTTP (DEC-PLAT-07, ADR-016). */
class SubscriptionJobsHttpTest extends HttpTest {

    private static final ObjectMapper JSON = new ObjectMapper();
    /** The scopes of the worker's token for platform-admin (07-api/authentication.md; dev-keys.sh). */
    private static final String WORKER_SCOPES = "outbox:relay jobs:run subscriptions:expire";
    private static final HttpServer IDENTITY = identityAuth();

    /** identity-auth's GET /internal/v1/owners: one owner for every barbershop. */
    private static HttpServer identityAuth() {
        try {
            HttpServer server = HttpServer.create(new InetSocketAddress("127.0.0.1", 0), 0);
            server.createContext("/internal/v1/owners", x -> {
                byte[] body = ("{\"data\":[{\"userId\":\"" + UUID.randomUUID()
                        + "\",\"fullName\":\"Andres Rojas\",\"email\":\"andres@example.com\"}]}")
                        .getBytes(StandardCharsets.UTF_8);
                x.sendResponseHeaders(200, body.length);
                x.getResponseBody().write(body);
                x.close();
            });
            server.start();
            return server;
        } catch (IOException e) {
            throw new IllegalStateException(e);
        }
    }

    @DynamicPropertySource
    static void identity(DynamicPropertyRegistry registry) {
        registry.add("IDENTITY_AUTH_API_URL", () -> "http://127.0.0.1:" + IDENTITY.getAddress().getPort());
    }

    @Test
    void a_trial_ending_soon_is_announced_once_and_relayed_to_the_worker() throws Exception {
        UUID shop = BARBERSHOPS.add("TRIAL", null, 55);                       // 5 days left
        String worker = serviceBearer("barber-saas-worker", WORKER_SCOPES);

        http.perform(post("/internal/v1/subscriptions/notices").header("Authorization", worker))
                .andExpect(status().isOk());
        http.perform(post("/internal/v1/subscriptions/notices").header("Authorization", worker))
                .andExpect(status().isOk()).andExpect(jsonPath("$.processed").value(0));

        JsonNode event = eventOf(worker, shop);
        assertEquals("PlanExpiringSoon", event.get("type").asText());
        assertEquals("DAYS_7", event.get("payload").get("threshold").asText());
        assertEquals("andres@example.com", event.get("payload").get("owners").get(0).get("email").asText());
        assertEquals("barbershop", event.get("aggregateType").asText());

        http.perform(post("/internal/v1/outbox-events/" + event.get("id").asText() + "/published")
                .header("Authorization", worker)).andExpect(status().isNoContent());
        http.perform(post("/internal/v1/outbox-events/" + UUID.randomUUID() + "/failed").header("Authorization", worker)
                .contentType(MediaType.APPLICATION_JSON).content("{\"reason\":\"x\"}")).andExpect(status().isNotFound());
    }

    @Test
    void an_expired_trial_writes_barbershop_suspended() throws Exception {
        UUID shop = BARBERSHOPS.add("TRIAL", null, 70);
        String worker = serviceBearer("barber-saas-worker", WORKER_SCOPES);

        http.perform(post("/internal/v1/trials/expire").header("Authorization", worker)).andExpect(status().isOk());

        JsonNode event = eventOf(worker, shop);
        assertEquals("BarbershopSuspended", event.get("type").asText());
        assertEquals("TRIAL_EXPIRED", event.get("payload").get("reason").asText());
    }

    @Test
    void suspending_paid_barbershops_needs_the_expire_scope() throws Exception {     // docs#159
        String relayOnly = serviceBearer("barber-saas-worker", "outbox:relay jobs:run");
        http.perform(post("/internal/v1/subscriptions/expire").header("Authorization", relayOnly))
                .andExpect(status().isForbidden());
        http.perform(post("/internal/v1/subscriptions/expire").header("Authorization", serviceBearer("barber-saas-worker")))
                .andExpect(status().isForbidden());
        http.perform(post("/internal/v1/subscriptions/expire")
                        .header("Authorization", serviceBearer("barber-saas-worker", "outbox:relay subscriptions:expire")))
                .andExpect(status().isOk());
        http.perform(post("/internal/v1/subscriptions/expire")
                        .header("Authorization", serviceBearer("barber-saas-workflow", "subscriptions:expire")))
                .andExpect(status().isForbidden());
    }

    @Test
    void each_internal_operation_needs_its_own_scope() throws Exception {             // docs#159
        String deliverOnly = serviceBearer("barber-saas-worker", "events:deliver");
        String relayOnly = serviceBearer("barber-saas-worker", "outbox:relay");
        for (String token : new String[] {deliverOnly, serviceBearer("barber-saas-worker")}) {
            http.perform(get("/internal/v1/outbox-events").header("Authorization", token))
                    .andExpect(status().isForbidden());
            http.perform(post("/internal/v1/outbox-events/" + UUID.randomUUID() + "/published")
                    .header("Authorization", token)).andExpect(status().isForbidden());
        }
        http.perform(post("/internal/v1/trials/expire").header("Authorization", relayOnly))
                .andExpect(status().isForbidden());
        http.perform(post("/internal/v1/subscriptions/notices").header("Authorization", relayOnly))
                .andExpect(status().isForbidden());
        http.perform(get("/internal/v1/outbox-events").header("Authorization", relayOnly))
                .andExpect(status().isOk());
    }

    @Test
    void the_outbox_gives_at_most_fifty_events_per_call() throws Exception {          // docs#160
        String worker = serviceBearer("barber-saas-worker", WORKER_SCOPES);
        for (String limit : new String[] {"51", "0"}) {
            http.perform(get("/internal/v1/outbox-events?limit=" + limit).header("Authorization", worker))
                    .andExpect(status().isBadRequest())
                    .andExpect(jsonPath("$.details[0].field").value("limit"));
        }
        http.perform(get("/internal/v1/outbox-events?limit=50").header("Authorization", worker))
                .andExpect(status().isOk());
    }

    @Test
    void only_the_worker_calls_them() throws Exception {
        for (String token : new String[] {superAdmin(), serviceBearer("barber-saas-workflow")}) {
            http.perform(post("/internal/v1/subscriptions/expire").header("Authorization", token))
                    .andExpect(status().isForbidden());
            http.perform(get("/internal/v1/outbox-events").header("Authorization", token))
                    .andExpect(status().isForbidden());
        }
    }

    private JsonNode eventOf(String worker, UUID shop) throws Exception {
        String body = http.perform(get("/internal/v1/outbox-events?limit=50").header("Authorization", worker))
                .andExpect(status().isOk()).andReturn().getResponse().getContentAsString();
        for (JsonNode e : JSON.readTree(body).get("data")) {
            if (shop.toString().equals(e.get("aggregateId").asText())) {
                return e;
            }
        }
        throw new AssertionError("no pending event for " + shop);
    }
}

package co.edu.corhuila.barbersaas.platformadmin.app;

import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.post;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.header;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.jsonPath;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

import org.junit.jupiter.api.Test;

class FoundationHttpTest extends HttpTest {

    @Test
    void the_liveness_probe_needs_no_token() throws Exception {
        http.perform(get("/health"))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.status").value("ok"));
    }

    @Test
    void a_protected_route_without_a_token_answers_401_with_the_envelope_and_the_correlation_id() throws Exception {
        http.perform(get("/api/v1/platform/plans").header("X-Correlation-Id", "corr-123"))
                .andExpect(status().isUnauthorized())
                .andExpect(header().string("X-Correlation-Id", "corr-123"))
                .andExpect(jsonPath("$.error").value("UNAUTHORIZED"))
                .andExpect(jsonPath("$.traceId").value("corr-123"));
    }

    @Test
    void a_forged_token_answers_401() throws Exception {
        http.perform(get("/api/v1/platform/barbershops").header("Authorization", "Bearer a.b.c"))
                .andExpect(status().isUnauthorized());
    }

    @Test
    void the_public_plan_list_needs_no_token() throws Exception {
        http.perform(get("/api/v1/plans")).andExpect(status().isNotFound());   // the filter lets it through; no route yet
    }

    @Test
    void an_internal_route_is_never_anonymous() throws Exception {
        http.perform(post("/internal/v1/trials/expire")).andExpect(status().isUnauthorized());
    }
}

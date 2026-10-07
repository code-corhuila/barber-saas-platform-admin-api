package co.edu.corhuila.barbersaas.platformadmin.app;

import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.delete;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.patch;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.post;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.put;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.jsonPath;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

import com.fasterxml.jackson.databind.ObjectMapper;
import java.util.UUID;
import org.junit.jupiter.api.Test;
import org.springframework.http.MediaType;

class BarbershopHttpTest extends HttpTest {

    private static final ObjectMapper JSON = new ObjectMapper();

    private String plan(String name) throws Exception {
        String response = http.perform(post("/api/v1/platform/plans").header("Authorization", superAdmin())
                        .header("Idempotency-Key", "key-" + UUID.randomUUID()).contentType(MediaType.APPLICATION_JSON)
                        .content("{\"name\":\"" + name + "\",\"priceCents\":100,\"maxBarbers\":2}"))
                .andExpect(status().isCreated()).andReturn().getResponse().getContentAsString();
        return JSON.readTree(response).get("id").asText();
    }

    @Test
    void super_admin_onboards_a_barbershop_in_trial_with_its_plan_and_a_retry_returns_it() throws Exception {
        String planId = plan("Onboarding plan");
        String body = "{\"name\":\"La Navaja\",\"city\":\"Neiva\",\"phone\":\"3001234567\",\"planId\":\"" + planId + "\"}";
        String key = "key-" + UUID.randomUUID();
        String first = http.perform(post("/api/v1/platform/barbershops").header("Authorization", superAdmin())
                        .header("Idempotency-Key", key).contentType(MediaType.APPLICATION_JSON).content(body))
                .andExpect(status().isCreated())
                .andExpect(jsonPath("$.status").value("TRIAL"))
                .andExpect(jsonPath("$.planId").value(planId))
                .andReturn().getResponse().getContentAsString();
        http.perform(post("/api/v1/platform/barbershops").header("Authorization", superAdmin())
                        .header("Idempotency-Key", key).contentType(MediaType.APPLICATION_JSON).content(body))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.id").value(JSON.readTree(first).get("id").asText()));
    }

    @Test
    void the_list_filters_by_status_and_the_trial_reports_the_days_left() throws Exception {
        UUID recent = BARBERSHOPS.add("TRIAL", null, 10);
        http.perform(get("/api/v1/platform/barbershops?status=TRIAL&limit=100").header("Authorization", superAdmin()))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.data[?(@.id == '" + recent + "')].status").value("TRIAL"));
        http.perform(get("/api/v1/platform/barbershops/" + recent + "/trial").header("Authorization", superAdmin()))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.daysRemaining").value(50))
                .andExpect(jsonPath("$.expired").value(false));
        http.perform(get("/api/v1/platform/barbershops?status=OPEN").header("Authorization", superAdmin()))
                .andExpect(status().isBadRequest());
    }

    @Test
    void status_moves_follow_the_lifecycle() throws Exception {
        UUID shop = BARBERSHOPS.add("ACTIVE", null, 90);
        http.perform(patch("/api/v1/platform/barbershops/" + shop + "/status").header("Authorization", superAdmin())
                        .contentType(MediaType.APPLICATION_JSON).content("{\"status\":\"CANCELLED\"}"))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.status").value("CANCELLED"));
        http.perform(patch("/api/v1/platform/barbershops/" + shop + "/status").header("Authorization", superAdmin())
                        .contentType(MediaType.APPLICATION_JSON).content("{\"status\":\"ACTIVE\"}"))
                .andExpect(status().isConflict())
                .andExpect(jsonPath("$.error").value("INVALID_STATUS_TRANSITION"));
        http.perform(patch("/api/v1/platform/barbershops/" + shop + "/status").header("Authorization", superAdmin())
                        .contentType(MediaType.APPLICATION_JSON).content("{\"status\":\"TRIAL\"}"))
                .andExpect(status().isBadRequest());
        http.perform(get("/api/v1/platform/barbershops/" + UUID.randomUUID()).header("Authorization", superAdmin()))
                .andExpect(status().isNotFound());
    }

    @Test
    void an_inactive_plan_cannot_be_assigned_and_a_plan_in_use_cannot_be_deactivated() throws Exception {
        String inUse = plan("Plan in use");
        String spare = plan("Spare plan");
        UUID shop = BARBERSHOPS.add("ACTIVE", null, 30);
        http.perform(put("/api/v1/platform/barbershops/" + shop + "/plan").header("Authorization", superAdmin())
                        .contentType(MediaType.APPLICATION_JSON).content("{\"planId\":\"" + inUse + "\"}"))
                .andExpect(status().isOk());
        http.perform(delete("/api/v1/platform/plans/" + inUse).header("Authorization", superAdmin()))
                .andExpect(status().isUnprocessableEntity());
        http.perform(delete("/api/v1/platform/plans/" + spare).header("Authorization", superAdmin()))
                .andExpect(status().isNoContent());
        http.perform(put("/api/v1/platform/barbershops/" + shop + "/plan").header("Authorization", superAdmin())
                        .contentType(MediaType.APPLICATION_JSON).content("{\"planId\":\"" + spare + "\"}"))
                .andExpect(status().isUnprocessableEntity())
                .andExpect(jsonPath("$.error").value("BUSINESS_RULE_VIOLATION"));
    }

    @Test
    void the_worker_expires_trials_and_nobody_else_can() throws Exception {
        UUID expired = BARBERSHOPS.add("TRIAL", null, 61);
        http.perform(post("/internal/v1/trials/expire?limit=100").header("Authorization", serviceBearer("barber-saas-worker")))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.remaining").value(false));
        http.perform(get("/api/v1/platform/barbershops/" + expired).header("Authorization", superAdmin()))
                .andExpect(jsonPath("$.status").value("SUSPENDED"));
        http.perform(post("/internal/v1/trials/expire").header("Authorization", superAdmin()))
                .andExpect(status().isForbidden());
    }

    @Test
    void the_workflow_assigns_the_plan_chosen_at_sign_up_and_nobody_else_can() throws Exception {
        String active = plan("Sign-up plan");
        String retired = plan("Retired plan");
        http.perform(delete("/api/v1/platform/plans/" + retired).header("Authorization", superAdmin()))
                .andExpect(status().isNoContent());
        UUID shop = BARBERSHOPS.add("TRIAL", null, 0);
        String workflow = serviceBearer("barber-saas-workflow");

        http.perform(put("/internal/v1/barbershops/" + shop + "/plan").header("Authorization", workflow)
                        .contentType(MediaType.APPLICATION_JSON).content("{\"planId\":\"" + active + "\"}"))
                .andExpect(status().isNoContent());
        http.perform(get("/api/v1/platform/barbershops/" + shop).header("Authorization", superAdmin()))
                .andExpect(jsonPath("$.planId").value(active))
                .andExpect(jsonPath("$.status").value("TRIAL"));
        for (String plan : new String[] {retired, UUID.randomUUID().toString()}) {
            http.perform(put("/internal/v1/barbershops/" + shop + "/plan").header("Authorization", workflow)
                            .contentType(MediaType.APPLICATION_JSON).content("{\"planId\":\"" + plan + "\"}"))
                    .andExpect(status().isUnprocessableEntity());
        }
        http.perform(put("/internal/v1/barbershops/" + shop + "/plan").header("Authorization", workflow)
                        .contentType(MediaType.APPLICATION_JSON).content("{}"))
                .andExpect(status().isBadRequest());
        http.perform(put("/internal/v1/barbershops/" + shop + "/plan").header("Authorization", superAdmin())
                        .contentType(MediaType.APPLICATION_JSON).content("{\"planId\":\"" + active + "\"}"))
                .andExpect(status().isForbidden());
    }

    @Test
    void a_barbershop_role_never_reaches_the_platform() throws Exception {
        http.perform(get("/api/v1/platform/barbershops").header("Authorization", bearer("ADMIN_BARBERSHOP", UUID.randomUUID())))
                .andExpect(status().isForbidden());
    }
}

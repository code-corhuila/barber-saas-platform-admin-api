package co.edu.corhuila.barbersaas.platformadmin.app;

import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.post;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.put;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.header;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.jsonPath;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

import com.fasterxml.jackson.databind.ObjectMapper;
import java.util.UUID;
import org.junit.jupiter.api.Test;
import org.springframework.http.MediaType;

class PlanHttpTest extends HttpTest {

    private static final ObjectMapper JSON = new ObjectMapper();

    private String create(String name, long cents) throws Exception {
        String body = "{\"name\":\"" + name + "\",\"priceCents\":" + cents + ",\"maxBarbers\":3}";
        String response = http.perform(post("/api/v1/platform/plans").header("Authorization", superAdmin())
                        .header("Idempotency-Key", "key-" + UUID.randomUUID())
                        .contentType(MediaType.APPLICATION_JSON).content(body))
                .andExpect(status().isCreated())
                .andReturn().getResponse().getContentAsString();
        return JSON.readTree(response).get("id").asText();
    }

    @Test
    void super_admin_creates_a_plan_and_a_retry_with_the_same_key_answers_200_with_the_same_plan() throws Exception {
        String body = "{\"name\":\"Retry plan\",\"priceCents\":100,\"maxBarbers\":2}";
        String key = "key-" + UUID.randomUUID();
        String first = http.perform(post("/api/v1/platform/plans").header("Authorization", superAdmin())
                        .header("Idempotency-Key", key).contentType(MediaType.APPLICATION_JSON).content(body))
                .andExpect(status().isCreated())
                .andExpect(header().exists("Location"))
                .andExpect(jsonPath("$.isActive").value(true))
                .andReturn().getResponse().getContentAsString();
        http.perform(post("/api/v1/platform/plans").header("Authorization", superAdmin())
                        .header("Idempotency-Key", key).contentType(MediaType.APPLICATION_JSON).content(body))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.id").value(JSON.readTree(first).get("id").asText()));
    }

    @Test
    void the_public_list_needs_no_token_and_shows_only_active_plans() throws Exception {
        create("Public plan", 200);
        http.perform(get("/api/v1/plans"))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.meta.page").value(1))
                .andExpect(jsonPath("$.data[?(@.name == 'Public plan')].isActive").value(true));
    }

    @Test
    void a_barbershop_role_cannot_manage_plans() throws Exception {
        http.perform(get("/api/v1/platform/plans").header("Authorization", bearer("ADMIN_BARBERSHOP", UUID.randomUUID())))
                .andExpect(status().isForbidden())
                .andExpect(jsonPath("$.error").value("FORBIDDEN"));
    }

    @Test
    void the_body_follows_the_contract() throws Exception {
        http.perform(post("/api/v1/platform/plans").header("Authorization", superAdmin())
                        .header("Idempotency-Key", "key-" + UUID.randomUUID()).contentType(MediaType.APPLICATION_JSON)
                        .content("{\"name\":\"\",\"priceCents\":-1,\"maxBarbers\":0,\"isActive\":false}"))
                .andExpect(status().isBadRequest())
                .andExpect(jsonPath("$.error").value("VALIDATION_ERROR"));
        http.perform(post("/api/v1/platform/plans").header("Authorization", superAdmin())
                        .contentType(MediaType.APPLICATION_JSON).content("{\"name\":\"No key\",\"priceCents\":1,\"maxBarbers\":1}"))
                .andExpect(status().isBadRequest());
    }

    @Test
    void a_repeated_name_is_a_400_on_the_name_field() throws Exception {
        create("Unique plan", 300);
        http.perform(post("/api/v1/platform/plans").header("Authorization", superAdmin())
                        .header("Idempotency-Key", "key-" + UUID.randomUUID()).contentType(MediaType.APPLICATION_JSON)
                        .content("{\"name\":\"Unique plan\",\"priceCents\":1,\"maxBarbers\":1}"))
                .andExpect(status().isBadRequest())
                .andExpect(jsonPath("$.details[0].field").value("name"));
    }

    @Test
    void super_admin_reads_and_edits_a_plan_and_an_unknown_one_is_404() throws Exception {
        String id = create("Editable plan", 400);
        http.perform(put("/api/v1/platform/plans/" + id).header("Authorization", superAdmin())
                        .contentType(MediaType.APPLICATION_JSON)
                        .content("{\"name\":\"Edited plan\",\"priceCents\":500,\"maxBarbers\":4,\"featuresJson\":\"{\\\"x\\\":1}\"}"))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.name").value("Edited plan"))
                .andExpect(jsonPath("$.priceCents").value(500));
        http.perform(get("/api/v1/platform/plans/" + id).header("Authorization", superAdmin()))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.maxBarbers").value(4));
        http.perform(get("/api/v1/platform/plans/" + UUID.randomUUID()).header("Authorization", superAdmin()))
                .andExpect(status().isNotFound());
    }
}

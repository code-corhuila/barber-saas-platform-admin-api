package co.edu.corhuila.barbersaas.platformadmin.adapter.in.http;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertNull;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

import co.edu.corhuila.barbersaas.platformadmin.adapter.in.http.ApiError.FieldError;
import co.edu.corhuila.barbersaas.platformadmin.adapter.in.http.ApiError.ValidationException;
import com.fasterxml.jackson.databind.ObjectMapper;
import java.util.Optional;
import java.util.Set;
import org.junit.jupiter.api.Test;

class JsonBodyTest {

    private final ObjectMapper json = new ObjectMapper();

    private JsonBody body(String text, String... allowed) throws Exception {
        return JsonBody.of(json.readTree(text), Set.of(allowed));
    }

    @Test
    void an_absent_field_is_null_and_an_explicit_null_is_empty() throws Exception {
        JsonBody b = body("{\"address\":null}", "address", "phone");

        assertEquals(Optional.empty(), b.optionalText("address", 255));
        assertNull(b.optionalText("phone", 20));
        b.validate();
    }

    @Test
    void a_field_the_schema_does_not_allow_is_a_400_naming_it() throws Exception {
        JsonBody b = body("{\"name\":\"Cut\",\"status\":\"ACTIVE\"}", "name");
        b.requiredText("name", 100);

        ValidationException e = assertThrows(ValidationException.class, b::validate);
        assertEquals("status", e.details().get(0).field());
    }

    @Test
    void every_shape_error_is_reported_at_once() throws Exception {
        JsonBody b = body("{\"durationMinutes\":4,\"priceCents\":-1,\"name\":\"" + "x".repeat(101) + "\"}",
                "name", "durationMinutes", "priceCents");
        b.requiredText("name", 100);
        b.integer("durationMinutes", true, 5);
        b.longValue("priceCents", 0);

        ValidationException e = assertThrows(ValidationException.class, b::validate);
        assertEquals(Set.of("name", "durationMinutes", "priceCents"),
                Set.copyOf(e.details().stream().map(FieldError::field).toList()));
    }

    @Test
    void a_field_that_may_be_absent_cannot_be_null() throws Exception {
        JsonBody b = body("{\"city\":null}", "city");
        assertNull(b.presentText("city", 80));
        assertThrows(ValidationException.class, b::validate);
    }

    @Test
    void the_body_must_be_an_object() throws Exception {
        assertThrows(ValidationException.class, () -> JsonBody.of(json.readTree("[1]"), Set.of()));
        assertThrows(ValidationException.class, () -> JsonBody.of(null, Set.of()));
        assertTrue(body("{}").isEmpty());
    }

    @Test
    void paging_defaults_and_limits_follow_the_shared_parameters() {
        assertEquals(20, Requests.page(null, null).limit());
        assertThrows(ValidationException.class, () -> Requests.page(0, 20));
        assertThrows(ValidationException.class, () -> Requests.page(1, 101));
        assertThrows(ValidationException.class, () -> Requests.idempotencyKey("short"));
        assertEquals("key-00000001", Requests.idempotencyKey("key-00000001"));
    }
}

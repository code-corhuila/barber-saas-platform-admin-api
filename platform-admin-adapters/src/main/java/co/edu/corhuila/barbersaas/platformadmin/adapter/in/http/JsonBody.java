package co.edu.corhuila.barbersaas.platformadmin.adapter.in.http;

import co.edu.corhuila.barbersaas.platformadmin.adapter.in.http.ApiError.FieldError;
import co.edu.corhuila.barbersaas.platformadmin.adapter.in.http.ApiError.ValidationException;
import com.fasterxml.jackson.databind.JsonNode;
import java.math.BigDecimal;
import java.util.ArrayList;
import java.util.List;
import java.util.Optional;
import java.util.Set;
import java.util.UUID;

/**
 * Reads a request body against its contract schema: every schema here is additionalProperties: false,
 * so an unknown field (status, planId, barbershopId, ratingAvg…) answers 400 instead of being ignored.
 * Tells "absent" (null) from "sent as null" (Optional.empty()) for partial updates. Collects every
 * shape error and throws them together; business rules stay in the domain.
 */
final class JsonBody {

    private final JsonNode node;
    private final List<FieldError> errors = new ArrayList<>();

    private JsonBody(JsonNode node) {
        this.node = node;
    }

    static JsonBody of(JsonNode node, Set<String> allowed) {
        if (node == null || !node.isObject()) {
            throw new ValidationException("the body must be a JSON object", List.of());
        }
        JsonBody body = new JsonBody(node);
        node.fieldNames().forEachRemaining(f -> {
            if (!allowed.contains(f)) {
                body.errors.add(new FieldError(f, "not allowed"));
            }
        });
        return body;
    }

    boolean isEmpty() {
        return node.isEmpty();
    }

    String requiredText(String field, int max) {
        JsonNode v = node.get(field);
        if (v == null || !v.isTextual() || v.asText().isBlank()) {
            errors.add(new FieldError(field, "required"));
            return null;
        }
        return checkLength(field, v.asText(), max);
    }

    /** Absent: null. Present (text or null): an Optional. */
    Optional<String> optionalText(String field, int max) {
        JsonNode v = node.get(field);
        if (v == null) {
            return null;
        }
        if (v.isNull()) {
            return Optional.empty();
        }
        if (!v.isTextual()) {
            errors.add(new FieldError(field, "must be a string"));
            return null;
        }
        return Optional.ofNullable(checkLength(field, v.asText(), max));
    }

    /** A field that may be absent but never null. */
    String presentText(String field, int max) {
        Optional<String> v = optionalText(field, max);
        if (v != null && v.isEmpty()) {
            errors.add(new FieldError(field, "cannot be null"));
            return null;
        }
        return v == null ? null : v.get();
    }

    /** The schema's minimum is a shape rule: below it answers 400, like a missing field. */
    Integer integer(String field, boolean required, int min) {
        JsonNode v = node.get(field);
        if (v == null) {
            if (required) {
                errors.add(new FieldError(field, "required"));
            }
            return null;
        }
        if (!v.isInt() || v.asInt() < min) {
            errors.add(new FieldError(field, "must be an integer of at least " + min));
            return null;
        }
        return v.asInt();
    }

    Long longValue(String field, long min) {
        JsonNode v = node.get(field);
        if (v == null || !v.isIntegralNumber() || !v.canConvertToLong() || v.asLong() < min) {
            errors.add(new FieldError(field, v == null ? "required" : "must be an integer of at least " + min));
            return null;
        }
        return v.asLong();
    }

    Optional<Boolean> bool(String field) {
        JsonNode v = node.get(field);
        if (v == null) {
            return Optional.empty();
        }
        if (!v.isBoolean()) {
            errors.add(new FieldError(field, "must be a boolean"));
            return Optional.empty();
        }
        return Optional.of(v.asBoolean());
    }

    Optional<BigDecimal> optionalNumber(String field) {
        JsonNode v = node.get(field);
        if (v == null) {
            return null;
        }
        if (v.isNull()) {
            return Optional.empty();
        }
        if (!v.isNumber()) {
            errors.add(new FieldError(field, "must be a number"));
            return null;
        }
        return Optional.of(v.decimalValue());
    }

    /** Absent or null: null. The schema's minimum and maximum are shape rules: outside them answers 400. */
    BigDecimal number(String field, int min, int max) {
        Optional<BigDecimal> v = optionalNumber(field);
        if (v == null || v.isEmpty()) {
            return null;
        }
        if (v.get().compareTo(BigDecimal.valueOf(min)) < 0 || v.get().compareTo(BigDecimal.valueOf(max)) > 0) {
            errors.add(new FieldError(field, "must be between " + min + " and " + max));
            return null;
        }
        return v.get();
    }

    UUID uuid(String field) {
        JsonNode v = node.get(field);
        try {
            return UUID.fromString(v.asText());
        } catch (RuntimeException e) {
            errors.add(new FieldError(field, v == null ? "required" : "must be a UUID"));
            return null;
        }
    }

    /** Throws every collected error at once, as one 400. */
    void validate() {
        if (!errors.isEmpty()) {
            throw new ValidationException("the request is not valid", errors);
        }
    }

    private String checkLength(String field, String value, int max) {
        if (value.length() > max) {
            errors.add(new FieldError(field, "at most " + max + " characters"));
        }
        return value;
    }
}

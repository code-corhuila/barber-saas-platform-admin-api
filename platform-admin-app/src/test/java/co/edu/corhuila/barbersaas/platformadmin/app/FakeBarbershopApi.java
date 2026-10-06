package co.edu.corhuila.barbersaas.platformadmin.app;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.fasterxml.jackson.databind.node.ArrayNode;
import com.fasterxml.jackson.databind.node.ObjectNode;
import com.sun.net.httpserver.HttpExchange;
import com.sun.net.httpserver.HttpServer;
import java.io.IOException;
import java.net.InetSocketAddress;
import java.net.URLDecoder;
import java.nio.charset.StandardCharsets;
import java.time.Duration;
import java.time.Instant;
import java.util.Comparator;
import java.util.HashMap;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.UUID;
import java.util.concurrent.ConcurrentHashMap;

/**
 * barbershop-api's internal operations for platform-admin (barbershop-service.yaml 1.4.0), in memory:
 * 403 without platform-admin's token, 404 for an unknown id, 409 outside the lifecycle.
 */
final class FakeBarbershopApi {

    static final String TOKEN = "platform-admin-service-token";
    private static final ObjectMapper JSON = new ObjectMapper();
    private static final Map<String, Set<String>> MOVES = Map.of(
            "TRIAL", Set.of("ACTIVE", "SUSPENDED"), "ACTIVE", Set.of("SUSPENDED", "CANCELLED"),
            "SUSPENDED", Set.of("ACTIVE", "CANCELLED"), "CANCELLED", Set.of());

    private final Map<UUID, ObjectNode> rows = new ConcurrentHashMap<>();
    private final Map<String, UUID> keys = new ConcurrentHashMap<>();
    private final HttpServer server;

    FakeBarbershopApi() {
        try {
            server = HttpServer.create(new InetSocketAddress("127.0.0.1", 0), 0);
            server.createContext("/internal/v1/barbershops", this::handle);
            server.start();
        } catch (IOException e) {
            throw new IllegalStateException(e);
        }
    }

    String url() {
        return "http://127.0.0.1:" + server.getAddress().getPort();
    }

    /** A barbershop created {@code daysAgo} days ago, with its 60-day trial. */
    UUID add(String status, UUID planId, int daysAgo) {
        Instant created = Instant.now().minus(Duration.ofDays(daysAgo));
        UUID id = UUID.randomUUID();
        rows.put(id, barbershop(id, "Barbería " + id.toString().substring(0, 4), "Neiva", "3001234567", status, planId,
                created));
        return id;
    }

    private static ObjectNode barbershop(UUID id, String name, String city, String phone, String status, UUID planId,
                                         Instant created) {
        ObjectNode b = JSON.createObjectNode();
        b.put("id", id.toString()).put("name", name).putNull("address").put("city", city).putNull("latitude")
                .putNull("longitude").put("phone", phone).putNull("whatsappNumber").putNull("logoUrl")
                .put("status", status).put("timezone", "America/Bogota").put("cancellationPolicyHours", 2)
                .put("trialEndsAt", created.plus(Duration.ofDays(60)).toString()).put("createdAt", created.toString())
                .put("updatedAt", created.toString());
        if (planId == null) {
            b.putNull("planId");
        } else {
            b.put("planId", planId.toString());
        }
        return b;
    }

    private void handle(HttpExchange x) throws IOException {
        if (!("Bearer " + TOKEN).equals(x.getRequestHeaders().getFirst("Authorization"))) {
            send(x, 403, null);
            return;
        }
        String[] parts = x.getRequestURI().getPath().split("/");   // "", internal, v1, barbershops, {id}, {sub}
        String method = x.getRequestMethod();
        if (parts.length == 4) {
            if (method.equals("GET")) {
                list(x);
            } else {
                create(x);
            }
            return;
        }
        ObjectNode b = rows.get(UUID.fromString(parts[4]));
        if (b == null) {
            send(x, 404, null);
            return;
        }
        JsonNode body = method.equals("GET") ? null : JSON.readTree(x.getRequestBody());
        if (parts.length == 5) {
            send(x, 200, b);
        } else if (parts[5].equals("status")) {
            String to = body.path("status").asText();
            if (!to.equals(b.path("status").asText()) && !MOVES.get(b.path("status").asText()).contains(to)) {
                send(x, 409, JSON.createObjectNode().put("error", "INVALID_STATUS_TRANSITION").put("message", "not allowed"));
                return;
            }
            send(x, 200, b.put("status", to));
        } else {
            send(x, b.path("status").asText().equals("CANCELLED") ? 409 : 200, b.put("planId", body.path("planId").asText()));
        }
    }

    private void list(HttpExchange x) throws IOException {
        Map<String, String> q = new HashMap<>();
        String raw = x.getRequestURI().getRawQuery();
        for (String pair : raw == null ? new String[0] : raw.split("&")) {
            String[] kv = pair.split("=", 2);
            q.put(kv[0], URLDecoder.decode(kv[1], StandardCharsets.UTF_8));
        }
        List<ObjectNode> all = rows.values().stream()
                .filter(b -> !q.containsKey("status") || b.path("status").asText().equals(q.get("status")))
                .filter(b -> !q.containsKey("planId") || b.path("planId").asText().equals(q.get("planId")))
                .filter(b -> !q.containsKey("trialEndsBefore")
                        || Instant.parse(b.path("trialEndsAt").asText()).isBefore(Instant.parse(q.get("trialEndsBefore"))))
                .sorted(Comparator.comparing((ObjectNode b) -> b.path("createdAt").asText()).reversed())
                .toList();
        int page = Integer.parseInt(q.getOrDefault("page", "1"));
        int limit = Integer.parseInt(q.getOrDefault("limit", "20"));
        ObjectNode answer = JSON.createObjectNode();
        ArrayNode data = answer.putArray("data");
        all.stream().skip((long) (page - 1) * limit).limit(limit).forEach(data::add);
        answer.putObject("meta").put("page", page).put("limit", limit).put("total", all.size())
                .put("totalPages", (all.size() + limit - 1) / limit);
        send(x, 200, answer);
    }

    private void create(HttpExchange x) throws IOException {
        String key = x.getRequestHeaders().getFirst("Idempotency-Key");
        UUID known = keys.get(key);
        if (known != null) {
            send(x, 200, rows.get(known));
            return;
        }
        JsonNode body = JSON.readTree(x.getRequestBody());
        UUID id = UUID.randomUUID();
        ObjectNode b = barbershop(id, body.path("name").asText(), body.path("city").asText(), body.path("phone").asText(),
                "TRIAL", null, Instant.now());
        rows.put(id, b);
        keys.put(key, id);
        send(x, 201, b);
    }

    private static void send(HttpExchange x, int status, JsonNode body) throws IOException {
        byte[] bytes = body == null ? new byte[0] : JSON.writeValueAsBytes(body);
        x.getResponseHeaders().add("Content-Type", "application/json");
        x.sendResponseHeaders(status, bytes.length == 0 ? -1 : bytes.length);
        if (bytes.length > 0) {
            x.getResponseBody().write(bytes);
        }
        x.close();
    }
}

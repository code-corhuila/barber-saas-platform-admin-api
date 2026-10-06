package co.edu.corhuila.barbersaas.platformadmin.adapter.out.http;

import co.edu.corhuila.barbersaas.platformadmin.adapter.in.http.CorrelationFilter;
import co.edu.corhuila.barbersaas.platformadmin.application.port.in.ApplicationException.InvalidStatusTransition;
import co.edu.corhuila.barbersaas.platformadmin.application.port.in.ApplicationException.NotFound;
import co.edu.corhuila.barbersaas.platformadmin.application.port.in.ApplicationException.Unavailable;
import co.edu.corhuila.barbersaas.platformadmin.application.port.in.Page;
import co.edu.corhuila.barbersaas.platformadmin.application.port.out.Barbershops;
import com.fasterxml.jackson.databind.DeserializationFeature;
import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.fasterxml.jackson.datatype.jsr310.JavaTimeModule;
import java.io.IOException;
import java.net.URI;
import java.net.URLEncoder;
import java.net.http.HttpClient;
import java.net.http.HttpRequest;
import java.net.http.HttpResponse;
import java.nio.charset.StandardCharsets;
import java.time.Duration;
import java.time.Instant;
import java.util.ArrayList;
import java.util.List;
import java.util.Map;
import java.util.UUID;
import org.slf4j.MDC;

/**
 * barbershop-api's internal operations (barbershop-service.yaml 1.4.0, DEC-SHOP-06) with this
 * service's own token, never a user's. Explicit limits (norm 5.3.10): 2 s to connect, 5 s per request,
 * no retries here: the SUPER_ADMIN repeats the action, the worker runs again the next day.
 */
public class HttpBarbershops implements Barbershops {

    static final Duration CONNECT_TIMEOUT = Duration.ofSeconds(2);
    static final Duration REQUEST_TIMEOUT = Duration.ofSeconds(5);

    private final String baseUrl;
    private final String serviceToken;
    private final HttpClient http = HttpClient.newBuilder().connectTimeout(CONNECT_TIMEOUT).build();
    private final ObjectMapper json = new ObjectMapper().registerModule(new JavaTimeModule())
            .configure(DeserializationFeature.FAIL_ON_UNKNOWN_PROPERTIES, false);

    public HttpBarbershops(String baseUrl, String serviceToken) {
        String url = baseUrl == null ? "" : baseUrl.strip();
        this.baseUrl = url.endsWith("/") ? url.substring(0, url.length() - 1) : url;
        this.serviceToken = serviceToken == null ? "" : serviceToken.strip();
    }

    @Override
    public Page<Barbershop> list(String status, UUID planId, Instant trialEndsBefore, Page.Request page) {
        List<String> query = new ArrayList<>(List.of("page=" + page.page(), "limit=" + page.limit()));
        if (status != null) {
            query.add("status=" + status);
        }
        if (planId != null) {
            query.add("planId=" + planId);
        }
        if (trialEndsBefore != null) {
            query.add("trialEndsBefore=" + URLEncoder.encode(trialEndsBefore.toString(), StandardCharsets.UTF_8));
        }
        JsonNode body = send("GET", "/internal/v1/barbershops?" + String.join("&", query), null, null).body();
        try {
            List<Barbershop> items = new ArrayList<>();
            for (JsonNode node : body.path("data")) {
                items.add(json.treeToValue(node, Barbershop.class));
            }
            return new Page<>(items, page.page(), page.limit(), body.path("meta").path("total").asLong(items.size()));
        } catch (IOException e) {
            throw new Unavailable("barbershop-api answered a list that cannot be read");
        }
    }

    @Override
    public Barbershop get(UUID id) {
        return read(send("GET", "/internal/v1/barbershops/" + id, null, null).body());
    }

    @Override
    public Barbershop changeStatus(UUID id, String status) {
        return read(send("PATCH", "/internal/v1/barbershops/" + id + "/status", Map.of("status", status), null).body());
    }

    @Override
    public Barbershop assignPlan(UUID id, UUID planId) {
        return read(send("PUT", "/internal/v1/barbershops/" + id + "/plan", Map.of("planId", planId), null).body());
    }

    @Override
    public Created create(NewBarbershop barbershop, String idempotencyKey) {
        Answer answer = send("POST", "/internal/v1/barbershops", barbershop, idempotencyKey);
        return new Created(read(answer.body()), answer.status() == 201);
    }

    private record Answer(int status, JsonNode body) { }

    private Answer send(String method, String path, Object body, String idempotencyKey) {
        if (baseUrl.isEmpty() || serviceToken.isEmpty()) {
            throw new Unavailable("BARBERSHOP_API_URL or SERVICE_TOKEN is not set");
        }
        HttpRequest.Builder request = HttpRequest.newBuilder(URI.create(baseUrl + path))
                .timeout(REQUEST_TIMEOUT)
                .header("Accept", "application/json")
                .header("Authorization", "Bearer " + serviceToken);
        String correlationId = MDC.get(CorrelationFilter.MDC_KEY);
        if (correlationId != null) {
            request.header("X-Correlation-Id", correlationId);
        }
        if (idempotencyKey != null) {
            request.header("Idempotency-Key", idempotencyKey);
        }
        try {
            if (body == null) {
                request.method(method, HttpRequest.BodyPublishers.noBody());
            } else {
                request.header("Content-Type", "application/json")
                        .method(method, HttpRequest.BodyPublishers.ofByteArray(json.writeValueAsBytes(body)));
            }
            HttpResponse<byte[]> response = http.send(request.build(), HttpResponse.BodyHandlers.ofByteArray());
            return answer(response);
        } catch (IOException e) {
            throw new Unavailable("barbershop-api unreachable or too slow (" + e.getClass().getSimpleName() + ")");
        } catch (InterruptedException e) {
            Thread.currentThread().interrupt();
            throw new Unavailable("barbershop-api call interrupted");
        }
    }

    /** 404 and 409 are the contract's answers; anything else means barbershop-api is not usable now. */
    private Answer answer(HttpResponse<byte[]> response) throws IOException {
        int status = response.statusCode();
        JsonNode body = response.body().length == 0 ? json.createObjectNode() : json.readTree(response.body());
        if (status == 404) {
            throw new NotFound("Barbershop");
        }
        if (status == 409) {
            throw new InvalidStatusTransition(body.path("message").asText("the barbershop cannot make that move"));
        }
        if (status != 200 && status != 201) {
            throw new Unavailable("barbershop-api answered " + status + " to " + response.request().method() + " "
                    + response.request().uri().getPath());
        }
        return new Answer(status, body);
    }

    private Barbershop read(JsonNode body) {
        try {
            return json.treeToValue(body, Barbershop.class);
        } catch (IOException | IllegalArgumentException e) {
            throw new Unavailable("barbershop-api answered a barbershop that cannot be read");
        }
    }
}

package co.edu.corhuila.barbersaas.platformadmin.adapter.out.http;

import co.edu.corhuila.barbersaas.platformadmin.adapter.in.http.CorrelationFilter;
import co.edu.corhuila.barbersaas.platformadmin.application.port.in.ApplicationException.Unavailable;
import co.edu.corhuila.barbersaas.platformadmin.application.port.out.Owners;
import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import java.io.IOException;
import java.net.URI;
import java.net.http.HttpClient;
import java.net.http.HttpRequest;
import java.net.http.HttpResponse;
import java.time.Duration;
import java.util.ArrayList;
import java.util.List;
import java.util.UUID;
import org.slf4j.MDC;

/**
 * identity-auth's GET /internal/v1/owners (DEC-AUTH-10) with this service's own token. Same limits as
 * HttpBarbershops: 2 s to connect, 5 s per request, no retries (the worker calls again the next day).
 */
public class HttpOwners implements Owners {

    private final String baseUrl;
    private final String serviceToken;
    private final HttpClient http = HttpClient.newBuilder().connectTimeout(HttpBarbershops.CONNECT_TIMEOUT).build();
    private final ObjectMapper json = new ObjectMapper();

    public HttpOwners(String baseUrl, String serviceToken) {
        String url = baseUrl == null ? "" : baseUrl.strip();
        this.baseUrl = url.endsWith("/") ? url.substring(0, url.length() - 1) : url;
        this.serviceToken = serviceToken == null ? "" : serviceToken.strip();
    }

    @Override
    public List<Owner> of(UUID barbershopId) {
        if (baseUrl.isEmpty() || serviceToken.isEmpty()) {
            throw new Unavailable("IDENTITY_AUTH_API_URL or SERVICE_TOKEN is not set");
        }
        HttpRequest.Builder request = HttpRequest.newBuilder(
                        URI.create(baseUrl + "/internal/v1/owners?barbershopId=" + barbershopId))
                .timeout(HttpBarbershops.REQUEST_TIMEOUT).GET()
                .header("Accept", "application/json")
                .header("Authorization", "Bearer " + serviceToken);
        String correlationId = MDC.get(CorrelationFilter.MDC_KEY);
        if (correlationId != null) {
            request.header("X-Correlation-Id", correlationId);
        }
        try {
            HttpResponse<byte[]> response = http.send(request.build(), HttpResponse.BodyHandlers.ofByteArray());
            if (response.statusCode() != 200) {
                throw new Unavailable("identity-auth answered " + response.statusCode() + " to the owners read");
            }
            List<Owner> owners = new ArrayList<>();
            for (JsonNode o : json.readTree(response.body()).path("data")) {
                owners.add(new Owner(UUID.fromString(o.path("userId").asText()), o.path("fullName").asText(),
                        o.path("email").asText()));
            }
            return owners;
        } catch (IOException | IllegalArgumentException e) {
            throw new Unavailable("identity-auth unreachable or unreadable (" + e.getClass().getSimpleName() + ")");
        } catch (InterruptedException e) {
            Thread.currentThread().interrupt();
            throw new Unavailable("identity-auth call interrupted");
        }
    }
}

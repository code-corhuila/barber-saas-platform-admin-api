package co.edu.corhuila.barbersaas.platformadmin.adapter.in.http;

import co.edu.corhuila.barbersaas.platformadmin.adapter.in.http.ApiError.FieldError;
import co.edu.corhuila.barbersaas.platformadmin.adapter.in.http.ApiError.ValidationException;
import co.edu.corhuila.barbersaas.platformadmin.adapter.in.http.Views.PageView;
import co.edu.corhuila.barbersaas.platformadmin.application.port.in.BarbershopUseCases;
import co.edu.corhuila.barbersaas.platformadmin.application.port.in.BarbershopUseCases.Onboarded;
import co.edu.corhuila.barbersaas.platformadmin.application.port.in.BarbershopUseCases.Trial;
import co.edu.corhuila.barbersaas.platformadmin.application.port.in.Caller;
import co.edu.corhuila.barbersaas.platformadmin.application.port.out.Barbershops.Barbershop;
import co.edu.corhuila.barbersaas.platformadmin.application.port.out.Barbershops.NewBarbershop;
import com.fasterxml.jackson.databind.JsonNode;
import java.math.BigDecimal;
import java.net.URI;
import java.util.List;
import java.util.Optional;
import java.util.Set;
import java.util.UUID;
import org.springframework.http.HttpStatus;
import org.springframework.http.ResponseEntity;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PatchMapping;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.PutMapping;
import org.springframework.web.bind.annotation.RequestAttribute;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestHeader;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RequestParam;
import org.springframework.web.bind.annotation.RestController;

/** HTTP to use case for the barbershops of the platform (tag Barbershops), SUPER_ADMIN only. */
@RestController
@RequestMapping("/api/v1/platform/barbershops")
public class BarbershopController {

    private static final Set<String> STATUSES = Set.of("TRIAL", "ACTIVE", "SUSPENDED", "CANCELLED");
    private static final Set<String> TARGETS = Set.of("ACTIVE", "SUSPENDED", "CANCELLED");
    private static final Set<String> ONBOARD_FIELDS = Set.of("name", "address", "city", "latitude", "longitude",
            "phone", "whatsappNumber", "logoUrl", "planId", "timezone", "cancellationPolicyHours");

    private final BarbershopUseCases barbershops;

    public BarbershopController(BarbershopUseCases barbershops) {
        this.barbershops = barbershops;
    }

    @GetMapping
    public PageView<BarbershopView> list(@RequestAttribute(AuthFilter.CALLER_ATTRIBUTE) Caller caller,
                                         @RequestParam(required = false) Integer page,
                                         @RequestParam(required = false) Integer limit,
                                         @RequestParam(required = false) String status) {
        if (status != null && !STATUSES.contains(status)) {
            throw invalid("status", "one of " + STATUSES);
        }
        return PageView.of(barbershops.list(caller, status, Requests.page(page, limit)), BarbershopView::of);
    }

    /**
     * Onboarding: barbershop-api creates it in TRIAL and this service assigns the plan. whatsappNumber,
     * logoUrl, timezone and cancellationPolicyHours are validated but set later by the owner: barbershop's
     * internal create does not take them.
     */
    @PostMapping
    public ResponseEntity<BarbershopView> onboard(@RequestAttribute(AuthFilter.CALLER_ATTRIBUTE) Caller caller,
                                                  @RequestHeader(value = "Idempotency-Key", required = false) String key,
                                                  @RequestBody(required = false) JsonNode json) {
        String idempotencyKey = Requests.idempotencyKey(key);
        JsonBody body = JsonBody.of(json, ONBOARD_FIELDS);
        String name = body.requiredText("name", 120);
        String city = body.requiredText("city", 80);
        String phone = body.requiredText("phone", 20);
        Optional<String> address = body.optionalText("address", 255);
        BigDecimal latitude = body.number("latitude", -90, 90);
        BigDecimal longitude = body.number("longitude", -180, 180);
        UUID planId = body.uuid("planId");
        body.optionalText("whatsappNumber", 20);
        body.optionalText("logoUrl", 255);
        body.optionalText("timezone", 50);
        if (json != null && json.has("cancellationPolicyHours")) {
            body.integer("cancellationPolicyHours", false, 0);
        }
        body.validate();
        NewBarbershop data = new NewBarbershop(name, city, address == null ? null : address.orElse(null), phone,
                latitude, longitude);
        Onboarded result = barbershops.onboard(caller, data, planId, idempotencyKey);
        if (!result.created()) {
            return ResponseEntity.ok(BarbershopView.of(result.barbershop()));
        }
        return ResponseEntity.status(HttpStatus.CREATED)
                .location(URI.create("/api/v1/platform/barbershops/" + result.barbershop().id()))
                .body(BarbershopView.of(result.barbershop()));
    }

    @GetMapping("/{id}")
    public BarbershopView get(@RequestAttribute(AuthFilter.CALLER_ATTRIBUTE) Caller caller, @PathVariable UUID id) {
        return BarbershopView.of(barbershops.get(caller, id));
    }

    @GetMapping("/{id}/trial")
    public Trial trial(@RequestAttribute(AuthFilter.CALLER_ATTRIBUTE) Caller caller, @PathVariable UUID id) {
        return barbershops.trial(caller, id);
    }

    @PatchMapping("/{id}/status")
    public BarbershopView changeStatus(@RequestAttribute(AuthFilter.CALLER_ATTRIBUTE) Caller caller,
                                       @PathVariable UUID id, @RequestBody(required = false) JsonNode json) {
        JsonBody body = JsonBody.of(json, Set.of("status"));
        String status = body.requiredText("status", 20);
        body.validate();
        if (!TARGETS.contains(status)) {
            throw invalid("status", "one of " + TARGETS + "; TRIAL is not a target");
        }
        return BarbershopView.of(barbershops.changeStatus(caller, id, status));
    }

    @PutMapping("/{id}/plan")
    public BarbershopView assignPlan(@RequestAttribute(AuthFilter.CALLER_ATTRIBUTE) Caller caller,
                                     @PathVariable UUID id, @RequestBody(required = false) JsonNode json) {
        JsonBody body = JsonBody.of(json, Set.of("planId"));
        UUID planId = body.uuid("planId");
        body.validate();
        return BarbershopView.of(barbershops.assignPlan(caller, id, planId));
    }

    private static ValidationException invalid(String field, String message) {
        return new ValidationException("the request is not valid", List.of(new FieldError(field, message)));
    }

    /** PlatformBarbershop of platform-admin-service.yaml. */
    record BarbershopView(UUID id, String name, String address, String city, BigDecimal latitude, BigDecimal longitude,
                          String phone, String whatsappNumber, String logoUrl, String status, UUID planId,
                          String timezone, int cancellationPolicyHours, java.time.Instant trialEndsAt,
                          java.time.Instant createdAt, java.time.Instant updatedAt) {
        static BarbershopView of(Barbershop b) {
            return new BarbershopView(b.id(), b.name(), b.address(), b.city(), b.latitude(), b.longitude(), b.phone(),
                    b.whatsappNumber(), b.logoUrl(), b.status(), b.planId(), b.timezone(), b.cancellationPolicyHours(),
                    b.trialEndsAt(), b.createdAt(), b.updatedAt());
        }
    }
}

package co.edu.corhuila.barbersaas.platformadmin.adapter.in.http;

import co.edu.corhuila.barbersaas.platformadmin.adapter.in.http.ApiError.FieldError;
import co.edu.corhuila.barbersaas.platformadmin.adapter.in.http.ApiError.ValidationException;
import co.edu.corhuila.barbersaas.platformadmin.adapter.in.http.Views.PageView;
import co.edu.corhuila.barbersaas.platformadmin.adapter.in.http.Views.PlanView;
import co.edu.corhuila.barbersaas.platformadmin.application.port.in.Caller;
import co.edu.corhuila.barbersaas.platformadmin.application.port.in.PaymentUseCases;
import co.edu.corhuila.barbersaas.platformadmin.application.port.in.PaymentUseCases.Checkout;
import co.edu.corhuila.barbersaas.platformadmin.application.port.in.PaymentUseCases.ProviderResult;
import co.edu.corhuila.barbersaas.platformadmin.application.port.in.PaymentUseCases.Subscription;
import co.edu.corhuila.barbersaas.platformadmin.domain.model.Payment;
import com.fasterxml.jackson.databind.JsonNode;
import java.time.Instant;
import java.util.List;
import java.util.Set;
import java.util.UUID;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.http.HttpStatus;
import org.springframework.http.ResponseEntity;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestAttribute;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestHeader;
import org.springframework.web.bind.annotation.RequestParam;
import org.springframework.web.bind.annotation.RestController;

/** The owner's subscription and plan payments (tag Payments, ADR-017). */
@RestController
public class PaymentController {

    private static final Logger log = LoggerFactory.getLogger(PaymentController.class);

    record SubscriptionView(UUID barbershopId, String status, PlanView plan, Instant trialEndsAt, Instant paidUntil) {
        static SubscriptionView of(Subscription s) {
            return new SubscriptionView(s.barbershopId(), s.status(), s.plan() == null ? null : PlanView.of(s.plan()),
                    s.trialEndsAt(), s.paidUntil());
        }
    }

    record PaymentView(UUID id, UUID barbershopId, UUID planId, String planName, String reference, long amountCents,
                       String currency, String status, String providerTransactionId, Instant periodEnd,
                       Instant createdAt, Instant updatedAt) {
        static PaymentView of(Payment p) {
            return new PaymentView(p.id(), p.barbershopId(), p.planId(), p.planName(), p.reference(), p.amountCents(),
                    p.currency(), p.status(), p.providerTransactionId(), p.periodEnd(), p.createdAt(), p.updatedAt());
        }
    }

    record CheckoutView(PaymentView payment, String checkoutUrl) {
        static CheckoutView of(Checkout c) {
            return new CheckoutView(PaymentView.of(c.payment()), c.checkoutUrl());
        }
    }

    private final PaymentUseCases payments;
    private final WompiSignature signature;

    public PaymentController(PaymentUseCases payments, WompiSignature signature) {
        this.payments = payments;
        this.signature = signature;
    }

    @GetMapping("/api/v1/subscription")
    public SubscriptionView subscription(@RequestAttribute(AuthFilter.CALLER_ATTRIBUTE) Caller caller) {
        return SubscriptionView.of(payments.subscription(caller));
    }

    @PostMapping("/api/v1/payments")
    public ResponseEntity<CheckoutView> start(@RequestAttribute(AuthFilter.CALLER_ATTRIBUTE) Caller caller,
                                              @RequestHeader(value = "Idempotency-Key", required = false) String key,
                                              @RequestBody(required = false) JsonNode json) {
        String idempotencyKey = Requests.idempotencyKey(key);
        JsonBody body = JsonBody.of(json, Set.of("planId"));
        UUID planId = body.uuid("planId");
        body.validate();
        Checkout checkout = payments.start(caller, planId, idempotencyKey);
        return ResponseEntity.status(checkout.created() ? HttpStatus.CREATED : HttpStatus.OK)
                .body(CheckoutView.of(checkout));
    }

    @GetMapping("/api/v1/payments")
    public PageView<PaymentView> list(@RequestAttribute(AuthFilter.CALLER_ATTRIBUTE) Caller caller,
                                      @RequestParam(required = false) UUID barbershopId,
                                      @RequestParam(required = false) Integer page,
                                      @RequestParam(required = false) Integer limit) {
        return PageView.of(payments.list(caller, barbershopId, Requests.page(page, limit)), PaymentView::of);
    }

    /** Called by Wompi, never by the app: no token, only the checksum (DEC-PLAT-06). */
    @PostMapping("/api/v1/payments/wompi/events")
    public ResponseEntity<Void> wompiEvent(@RequestBody(required = false) JsonNode event) {
        if (!signature.authentic(event)) {
            log.warn("a Wompi event with a wrong checksum was refused");
            return ResponseEntity.status(HttpStatus.UNAUTHORIZED).build();
        }
        if (!"transaction.updated".equals(event.path("event").asText())) {
            return ResponseEntity.ok().build();
        }
        JsonNode transaction = event.path("data").path("transaction");
        String reference = transaction.path("reference").asText("");
        if (reference.isEmpty() || !transaction.path("amount_in_cents").canConvertToLong()) {
            throw new ValidationException("the event is not valid",
                    List.of(new FieldError("data.transaction", "reference and amount_in_cents are required")));
        }
        payments.apply(new ProviderResult(reference, transaction.path("id").asText(null),
                transaction.path("status").asText(""), transaction.path("amount_in_cents").asLong()));
        return ResponseEntity.ok().build();
    }
}

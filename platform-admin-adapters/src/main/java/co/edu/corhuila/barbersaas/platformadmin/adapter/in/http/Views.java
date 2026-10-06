package co.edu.corhuila.barbersaas.platformadmin.adapter.in.http;

import co.edu.corhuila.barbersaas.platformadmin.application.port.in.Page;
import co.edu.corhuila.barbersaas.platformadmin.domain.model.SubscriptionPlan;
import java.time.Instant;
import java.util.List;
import java.util.UUID;
import java.util.function.Function;

/** The response bodies of platform-admin-service.yaml; field names as the contract writes them. */
final class Views {

    private Views() {
    }

    record Meta(int page, int limit, long total, long totalPages) { }

    /** {data, meta} of _shared.yaml (norm 5.3.6). */
    record PageView<T>(List<T> data, Meta meta) {
        static <D, T> PageView<T> of(Page<D> page, Function<D, T> view) {
            return new PageView<>(page.items().stream().map(view).toList(),
                    new Meta(page.page(), page.limit(), page.total(), page.totalPages()));
        }
    }

    record PlanView(UUID id, String name, long priceCents, int maxBarbers, String featuresJson, boolean isActive,
                    Instant createdAt) {
        static PlanView of(SubscriptionPlan p) {
            return new PlanView(p.id(), p.name(), p.priceCents(), p.maxBarbers(), p.featuresJson(), p.active(),
                    p.createdAt());
        }
    }
}

package co.edu.corhuila.barbersaas.platformadmin.adapter.in.http;

import co.edu.corhuila.barbersaas.platformadmin.application.port.in.Caller;
import co.edu.corhuila.barbersaas.platformadmin.application.port.in.MetricsUseCases;
import co.edu.corhuila.barbersaas.platformadmin.application.port.in.MetricsUseCases.Metrics;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.RequestAttribute;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RestController;

/** getPlatformMetrics (tag Metrics, FR-023, DEC-PLAT-09), SUPER_ADMIN only. */
@RestController
@RequestMapping("/api/v1/platform/metrics")
public class MetricsController {

    private final MetricsUseCases metrics;

    public MetricsController(MetricsUseCases metrics) {
        this.metrics = metrics;
    }

    @GetMapping
    public Metrics get(@RequestAttribute(AuthFilter.CALLER_ATTRIBUTE) Caller caller) {
        return metrics.metrics(caller);
    }
}

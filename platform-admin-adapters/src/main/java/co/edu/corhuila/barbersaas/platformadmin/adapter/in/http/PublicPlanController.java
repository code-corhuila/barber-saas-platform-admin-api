package co.edu.corhuila.barbersaas.platformadmin.adapter.in.http;

import co.edu.corhuila.barbersaas.platformadmin.adapter.in.http.Views.PageView;
import co.edu.corhuila.barbersaas.platformadmin.adapter.in.http.Views.PlanView;
import co.edu.corhuila.barbersaas.platformadmin.application.port.in.PlanUseCases;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.RequestParam;
import org.springframework.web.bind.annotation.RestController;

/** GET /api/v1/plans: the active plans, cheapest first, without a token (FR-004, self-registration). */
@RestController
public class PublicPlanController {

    private final PlanUseCases plans;

    public PublicPlanController(PlanUseCases plans) {
        this.plans = plans;
    }

    @GetMapping("/api/v1/plans")
    public PageView<PlanView> listActive(@RequestParam(required = false) Integer page,
                                         @RequestParam(required = false) Integer limit) {
        return PageView.of(plans.listActive(Requests.page(page, limit)), PlanView::of);
    }
}

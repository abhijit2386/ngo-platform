package org.ngoplatform.subscription.api;

import java.math.BigDecimal;
import org.ngoplatform.subscription.app.Catalog;
import org.ngoplatform.subscription.app.Pricing;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.RestController;

/** SUB-002/003: public plan catalogue (prices include GST; 0% donation commission). */
@RestController
public class PlansController {
    private final Catalog catalog;

    public PlansController(Catalog catalog) { this.catalog = catalog; }

    @GetMapping("/api/v1/public/plans")
    public Dtos.Catalogue plans() {
        Pricing pricing = catalog.pricing();
        var plans = catalog.activePlans().stream().map(p -> new Dtos.PlanView(p.getCode(), p.getName(),
                p.getMonthlyPrice(), pricing.periodPrice(p.getMonthlyPrice(), Pricing.Cycle.QUARTERLY),
                pricing.periodPrice(p.getMonthlyPrice(), Pricing.Cycle.ANNUAL), p.getIncludedStaff(), p.getAccentColor(),
                p.isStaffBlocksAllowed(), catalog.entitlements(p.getCode()))).toList();
        return new Dtos.Catalogue(plans, catalog.intSetting("trial_days"), catalog.intSetting("grace_days"),
                new BigDecimal(catalog.setting("annual_discount_percent")), catalog.intSetting("staff_block_size"),
                new BigDecimal(catalog.setting("staff_block_monthly_price")),
                pricing.blockPeriodPrice(Pricing.Cycle.QUARTERLY), true, "0%");
    }
}

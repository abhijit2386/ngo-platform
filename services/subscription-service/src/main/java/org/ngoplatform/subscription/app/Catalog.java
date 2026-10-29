package org.ngoplatform.subscription.app;

import java.math.BigDecimal;
import java.util.HashMap;
import java.util.List;
import java.util.Map;
import org.ngoplatform.commons.error.ApiException;
import org.ngoplatform.subscription.domain.Plan;
import org.ngoplatform.subscription.domain.Repos;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.stereotype.Component;

/** Database-driven plan catalogue, entitlements and billing settings (spec §6.6). */
@Component
public class Catalog {
    private final Repos.Plans plans;
    private final JdbcTemplate jdbc;

    public Catalog(Repos.Plans plans, JdbcTemplate jdbc) {
        this.plans = plans;
        this.jdbc = jdbc;
    }

    public Plan plan(String code) {
        return plans.findById(code).filter(Plan::isActive)
                .orElseThrow(() -> ApiException.badRequest("UNKNOWN_PLAN", "Unknown plan " + code));
    }

    public List<Plan> activePlans() { return plans.findByActiveTrueOrderByRank(); }

    public Map<String, String> entitlements(String planCode) {
        Map<String, String> m = new HashMap<>();
        jdbc.query("select key, value from plan_entitlements where plan_code = ?",
                rs -> { m.put(rs.getString(1), rs.getString(2)); }, planCode);
        return m;
    }

    public String setting(String key) {
        return jdbc.queryForObject("select value from billing_settings where key = ?", String.class, key);
    }

    public int intSetting(String key) { return Integer.parseInt(setting(key)); }

    public Pricing pricing() {
        return new Pricing(new Pricing.Settings(new BigDecimal(setting("annual_discount_percent")),
                new BigDecimal(setting("staff_block_monthly_price")), intSetting("staff_block_size"),
                new BigDecimal(setting("gst_percent"))));
    }

    public void updateSetting(String key, String value) {
        int n = jdbc.update("update billing_settings set value = ? where key = ?", value, key);
        if (n == 0) throw ApiException.notFound("Setting " + key);
    }

    public void updateEntitlement(String plan, String key, String value) {
        jdbc.update("insert into plan_entitlements(plan_code, key, value) values (?,?,?) "
                + "on conflict (plan_code, key) do update set value = excluded.value", plan, key, value);
    }
}

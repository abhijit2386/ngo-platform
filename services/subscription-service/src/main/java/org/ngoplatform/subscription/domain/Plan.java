package org.ngoplatform.subscription.domain;

import jakarta.persistence.*;
import java.math.BigDecimal;

@Entity
@Table(name = "subscription_plans")
public class Plan {
    @Id private String code;
    @Column(nullable = false) private String name;
    @Column(name = "monthly_price", nullable = false) private BigDecimal monthlyPrice;
    @Column(name = "included_staff", nullable = false) private int includedStaff;
    @Column(nullable = false) private int rank;
    @Column(name = "accent_color", nullable = false) private String accentColor;
    @Column(nullable = false) private boolean active;
    @Column(name = "staff_blocks_allowed", nullable = false) private boolean staffBlocksAllowed;

    protected Plan() {}

    public String getCode() { return code; }
    public String getName() { return name; }
    public BigDecimal getMonthlyPrice() { return monthlyPrice; }
    public int getIncludedStaff() { return includedStaff; }
    public int getRank() { return rank; }
    public String getAccentColor() { return accentColor; }
    public boolean isActive() { return active; }
    public boolean isStaffBlocksAllowed() { return staffBlocksAllowed; }
    public void updatePrice(BigDecimal monthly) { this.monthlyPrice = monthly; }
    public void setActive(boolean active) { this.active = active; }
}

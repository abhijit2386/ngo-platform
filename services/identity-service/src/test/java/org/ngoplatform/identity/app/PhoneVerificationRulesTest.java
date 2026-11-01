package org.ngoplatform.identity.app;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.Mockito.mock;

import java.time.Clock;
import java.util.HashSet;
import java.util.Set;
import java.util.UUID;
import org.junit.jupiter.api.Test;
import org.ngoplatform.commons.audit.AuditLog;
import org.ngoplatform.identity.domain.Repositories;
import org.ngoplatform.identity.sms.SmsSender;
import org.springframework.jdbc.core.JdbcTemplate;

class PhoneVerificationRulesTest {
    private final PhoneVerificationService svc = new PhoneVerificationService(mock(JdbcTemplate.class), mock(MeService.class),
            mock(Repositories.Users.class), mock(SmsSender.class), mock(AuditLog.class), Clock.systemUTC(), "test-secret");

    @Test
    void acceptsTenDigitIndianMobiles() {
        assertThat(PhoneVerificationService.isIndianMobile("9876543210")).isTrue();
        assertThat(PhoneVerificationService.isIndianMobile("6000000000")).isTrue();
    }

    @Test
    void rejectsLandlinesShortLongAndFormatted() {
        assertThat(PhoneVerificationService.isIndianMobile("5876543210")).isFalse();
        assertThat(PhoneVerificationService.isIndianMobile("987654321")).isFalse();
        assertThat(PhoneVerificationService.isIndianMobile("98765432101")).isFalse();
        assertThat(PhoneVerificationService.isIndianMobile("+919876543210")).isFalse();
        assertThat(PhoneVerificationService.isIndianMobile("98765 43210")).isFalse();
        assertThat(PhoneVerificationService.isIndianMobile(null)).isFalse();
    }

    @Test
    void masksAllButFirstAndLastTwoDigits() {
        assertThat(PhoneVerificationService.mask("9876543210")).isEqualTo("98XXXXXX10");
    }

    @Test
    void codesAreSixDigitsIncludingLeadingZeros() {
        for (int i = 0; i < 500; i++) assertThat(PhoneVerificationService.newCode()).matches("\\d{6}");
    }

    @Test
    void codesAreNotPredictable() {
        Set<String> seen = new HashSet<>();
        for (int i = 0; i < 200; i++) seen.add(PhoneVerificationService.newCode());
        assertThat(seen.size()).isGreaterThan(190);
    }

    @Test
    void hashBindsUserNumberAndCode() {
        UUID a = UUID.randomUUID(), b = UUID.randomUUID();
        String h = svc.hash(a, "9876543210", "123456");
        assertThat(h).hasSize(64).isEqualTo(svc.hash(a, "9876543210", "123456"));
        assertThat(h).isNotEqualTo(svc.hash(b, "9876543210", "123456"));
        assertThat(h).isNotEqualTo(svc.hash(a, "9876543211", "123456"));
        assertThat(h).isNotEqualTo(svc.hash(a, "9876543210", "123457"));
    }

    @Test
    void hashDependsOnTheServerSecret() {
        var other = new PhoneVerificationService(mock(JdbcTemplate.class), mock(MeService.class), mock(Repositories.Users.class),
                mock(SmsSender.class), mock(AuditLog.class), Clock.systemUTC(), "another-secret");
        UUID u = UUID.randomUUID();
        assertThat(svc.hash(u, "9876543210", "123456")).isNotEqualTo(other.hash(u, "9876543210", "123456"));
    }

    @Test
    void otpTextMatchesTheDltTemplate() {
        assertThat(SmsSender.otpText("042137"))
                .isEqualTo("042137 is your Yogdaan verification code. It expires in 5 minutes. Do not share it with anyone.");
    }

    @Test
    void limitsMatchThePolicy() {
        assertThat(PhoneVerificationService.TTL.toMinutes()).isEqualTo(5);
        assertThat(PhoneVerificationService.RESEND_AFTER.toSeconds()).isEqualTo(60);
        assertThat(PhoneVerificationService.MAX_TRIES).isEqualTo(5);
        assertThat(PhoneVerificationService.DAILY_LIMIT).isEqualTo(5);
    }
}

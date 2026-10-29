package org.ngoplatform.donation.app;

import org.springframework.scheduling.annotation.Scheduled;
import org.springframework.stereotype.Component;

@Component
public class Jobs {
    private final DonationService service;
    public Jobs(DonationService service) { this.service = service; }

    @Scheduled(fixedDelayString = "${ngo.payments.recovery-ms:60000}", initialDelay = 45000)
    public void recover() {
        service.recoverStale();
        service.pollRefunds();
    }
}

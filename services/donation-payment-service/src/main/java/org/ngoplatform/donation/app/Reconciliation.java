package org.ngoplatform.donation.app;

import java.math.BigDecimal;
import java.time.Clock;
import java.time.Duration;
import java.time.Instant;
import java.time.LocalDate;
import java.util.HashSet;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.UUID;
import org.ngoplatform.commons.audit.AuditLog;
import org.ngoplatform.commons.error.ApiException;
import org.ngoplatform.commons.events.EventPublisher;
import org.ngoplatform.commons.events.EventTypes;
import org.ngoplatform.donation.domain.Donation;
import org.ngoplatform.donation.domain.Records;
import org.ngoplatform.donation.domain.Repos;
import org.ngoplatform.donation.ledger.Ledger;
import org.ngoplatform.donation.provider.PaymentProvider;
import org.ngoplatform.donation.provider.Providers;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

/**
 * Settlement & provider reconciliation (spec §14): fetch settlements, match lines to captured payments, post
 * fee/tax/net journal entries, and raise exceptions for anything that does not match. Nothing is auto-corrected.
 */
@Service
public class Reconciliation {
    private final Repos.Settings settings;
    private final Repos.Batches batches;
    private final Repos.Lines lines;
    private final Repos.Payments payments;
    private final Repos.Donations donations;
    private final Repos.Runs runs;
    private final Repos.Exceptions exceptions;
    private final Providers providers;
    private final Ledger ledger;
    private final EventPublisher publisher;
    private final AuditLog audit;
    private final Clock clock;

    public Reconciliation(Repos.Settings settings, Repos.Batches batches, Repos.Lines lines, Repos.Payments payments,
                          Repos.Donations donations, Repos.Runs runs, Repos.Exceptions exceptions, Providers providers,
                          Ledger ledger, EventPublisher publisher, AuditLog audit, Clock clock) {
        this.settings = settings; this.batches = batches; this.lines = lines; this.payments = payments;
        this.donations = donations; this.runs = runs; this.exceptions = exceptions; this.providers = providers;
        this.ledger = ledger; this.publisher = publisher; this.audit = audit; this.clock = clock;
    }

    @Transactional
    public Records.ReconRun run(UUID ngoId, UUID actor) {
        Records.PaymentSettings ps = settings.findById(ngoId)
                .orElseThrow(() -> ApiException.rule("PAYMENTS_NOT_CONFIGURED", "Online payments are not configured"));
        PaymentProvider provider = providers.get(ps.getProvider());
        Records.ReconRun run = runs.save(new Records.ReconRun(ngoId, actor));
        LocalDate today = LocalDate.now(clock);
        int batchCount = 0, matched = 0, exc = 0;
        for (PaymentProvider.Settlement s : provider.getSettlements(ps.getMerchantReference(), today.minusDays(30), today)) {
            if (batches.existsByProviderAndProviderSettlementId(provider.name(), s.settlementId())) {
                exceptions.save(new Records.ReconException(ngoId, run.getId(), "DUPLICATE_SETTLEMENT", null, s.settlementId(), null, null));
                exc++;
                continue;
            }
            BigDecimal gross = BigDecimal.ZERO, fee = BigDecimal.ZERO, tax = BigDecimal.ZERO, net = BigDecimal.ZERO;
            for (PaymentProvider.SettlementLine l : s.lines()) {
                gross = gross.add(l.gross()); fee = fee.add(l.fee()); tax = tax.add(l.tax()); net = net.add(l.net());
            }
            Records.SettlementBatch b = batches.save(new Records.SettlementBatch(ngoId, provider.name(), s.settlementId(),
                    s.settledOn(), gross, fee, tax, net, s.bankReference()));
            batchCount++;
            boolean batchOk = true;
            for (PaymentProvider.SettlementLine l : s.lines()) {
                Records.SettlementLine line = lines.save(new Records.SettlementLine(b.getId(), l.paymentId(), l.gross(), l.fee(), l.tax(), l.net()));
                Records.Payment p = payments.findByProviderPaymentId(l.paymentId()).orElse(null);
                if (p == null || !p.getNgoId().equals(ngoId)) {
                    exceptions.save(new Records.ReconException(ngoId, run.getId(), "UNMATCHED_SETTLEMENT", null, l.paymentId(), null, l.gross()));
                    exc++; batchOk = false;
                    continue;
                }
                Donation d = donations.findById(p.getDonationId()).orElseThrow();
                if (d.getAmount().compareTo(l.gross()) != 0) {
                    exceptions.save(new Records.ReconException(ngoId, run.getId(), "AMOUNT_MISMATCH", d.getId(), l.paymentId(), d.getAmount(), l.gross()));
                    exc++; batchOk = false;
                    continue;
                }
                if (d.getStatus() != Donation.Status.PAID && d.getStatus() != Donation.Status.PARTIALLY_REFUNDED
                        && d.getStatus() != Donation.Status.REFUNDED) {
                    exceptions.save(new Records.ReconException(ngoId, run.getId(), "STATUS_MISMATCH", d.getId(), l.paymentId(), d.getAmount(), l.gross()));
                    exc++; batchOk = false;
                    continue;
                }
                line.match(d.getId());
                ledger.post(ngoId, "SETTLEMENT", l.paymentId(), "Settlement " + s.settlementId(), List.of(
                        Ledger.Line.dr(Ledger.BANK, l.net()), Ledger.Line.dr(Ledger.GATEWAY_FEES, l.fee()),
                        Ledger.Line.dr(Ledger.GST_ON_FEES, l.tax()), Ledger.Line.cr(Ledger.GATEWAY_RECEIVABLE, l.gross())));
                matched++;
            }
            b.setStatus(batchOk ? "RECONCILED" : "EXCEPTION");
        }
        // Captured payments older than 3 days that never appeared in any settlement.
        Set<UUID> raised = new HashSet<>();
        for (Records.Payment p : payments.findByNgoIdAndStatus(ngoId, "PAID")) {
            if (p.getProviderPaymentId() == null || lines.existsByProviderPaymentId(p.getProviderPaymentId())) continue;
            Instant cutoff = clock.instant().minus(Duration.ofDays(3));
            if (p.getCreatedAt().isBefore(cutoff) && raised.add(p.getDonationId())) {
                exceptions.save(new Records.ReconException(ngoId, run.getId(), "MISSING_SETTLEMENT", p.getDonationId(),
                        p.getProviderPaymentId(), p.getAmount(), null));
                exc++;
            }
        }
        run.finish(batchCount, matched, exc);
        if (exc > 0) publisher.publish(EventTypes.RECON_EXCEPTION, ngoId, run.getId(), Map.of("count", exc));
        audit.record(ngoId, actor, "RECONCILIATION_RUN", "RECON_RUN", run.getId(), Map.of("matched", matched, "exceptions", exc));
        return run;
    }

    @Transactional
    public Records.ReconException resolve(UUID ngoId, UUID actor, UUID exceptionId, boolean ignore, String note) {
        Records.ReconException e = exceptions.findByIdAndNgoId(exceptionId, ngoId).orElseThrow(() -> ApiException.notFound("Exception"));
        if (!"OPEN".equals(e.getStatus())) throw ApiException.rule("ALREADY_RESOLVED", "Already resolved");
        if (note == null || note.isBlank()) throw ApiException.badRequest("NOTE_REQUIRED", "Explain how this was resolved");
        e.resolve(ignore ? "IGNORED" : "RESOLVED", note, actor);
        audit.record(ngoId, actor, "RECON_EXCEPTION_" + e.getStatus(), "RECON_EXCEPTION", exceptionId, Map.of());
        return e;
    }
}

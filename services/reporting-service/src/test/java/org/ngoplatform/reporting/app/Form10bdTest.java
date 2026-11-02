package org.ngoplatform.reporting.app;

import static org.assertj.core.api.Assertions.assertThat;

import java.math.BigDecimal;
import java.time.Instant;
import java.util.List;
import java.util.Map;
import org.junit.jupiter.api.Test;

class Form10bdTest {
    private static Data.TaxRow row(String id, String name, String pan, String channel, String amount) {
        return new Data.TaxRow(id, Instant.parse("2026-05-01T05:00:00Z"), name, pan, "12 MG Road, Pune", channel, new BigDecimal(amount), "Books");
    }

    @Test
    void groupsByDonorAndModeAndKeepsReceiptNumbers() {
        List<Data.TaxRow> rows = List.of(
                row("d1", "Meera Iyer", "abcde1234f", "ONLINE", "1500.00"),
                row("d2", "Meera Iyer", "ABCDE1234F", "UPI_DIRECT", "500.00"),
                row("d3", "Meera Iyer", "ABCDE1234F", "CASH", "1000.00"),
                row("d4", "Kiran Rao", "PQRSX9876Z", "CHEQUE", "2500.00"));
        var register = Form10bd.register(rows, new Data.ReceiptFacts(Map.of("d1", "80G/26-27/0001", "d2", "80G/26-27/0002", "d3", "80G/26-27/0003", "d4", "80G/26-27/0004"), List.of()));
        assertThat(register.get(0).pan()).isEqualTo("ABCDE1234F");
        var lines = Form10bd.lines(register);
        assertThat(lines).hasSize(3);
        Form10bd.Line meeraElectronic = lines.get(0);
        assertThat(meeraElectronic.mode()).isEqualTo(Form10bd.MODE_ELECTRONIC);
        assertThat(meeraElectronic.amount()).isEqualByComparingTo("2000.00");
        assertThat(meeraElectronic.donations()).isEqualTo(2);
        assertThat(meeraElectronic.receiptNumbers()).isEqualTo("80G/26-27/0001, 80G/26-27/0002");
        assertThat(meeraElectronic.idCode()).isEqualTo("PAN");
        assertThat(meeraElectronic.section()).isEqualTo("Section 80G");
        assertThat(meeraElectronic.note()).isEmpty();
        assertThat(lines.get(1).mode()).isEqualTo(Form10bd.MODE_CASH);
        assertThat(lines.get(1).note()).isEmpty(); // cash up to Rs. 2,000 per donation is fine
        assertThat(lines.get(2).name()).isEqualTo("Kiran Rao");
    }

    @Test
    void flagsLinesTheAccountantMustCheck() {
        var lines = Form10bd.lines(Form10bd.register(List.of(
                row("a", "Anil", null, "ONLINE", "300.00"),
                row("b", "Ravi", "ABCDE1234F", "CASH", "1500.00"),
                row("c", "Ravi", "ABCDE1234F", "CASH", "2500.00"),
                row("d", "Sita", "PQRSX9876Z", "IN_KIND_VALUE", "800.00")), new Data.ReceiptFacts(Map.of("b", "R-1", "c", "R-2", "d", "R-3"), List.of())));
        assertThat(lines.get(0).note()).contains("PAN missing").contains("Receipt not issued");
        assertThat(lines.get(0).idCode()).isEmpty();
        assertThat(lines.get(1).amount()).isEqualByComparingTo("4000.00");
        assertThat(lines.get(1).note()).contains("1 cash donation(s) above Rs. 2,000");
        assertThat(lines.get(2).note()).contains("in kind");
    }

    @Test
    void leavesOutDonationsWhoseReceiptIsNot80g() {
        var register = Form10bd.register(List.of(row("a", "Meera Iyer", "ABCDE1234F", "ONLINE", "500.00"),
                row("b", "Kiran Joshi", "PQRSX9876Z", "ONLINE", "700.00"), row("c", "Anil Shah", "LMNOP4321Q", "UPI_DIRECT", "900.00")),
                new Data.ReceiptFacts(Map.of("a", "80G/1"), List.of("b")));
        assertThat(register).extracting(Form10bd.RegisterLine::donationId).containsExactly("a", "c");
        assertThat(Form10bd.lines(register).get(1).note()).contains("Receipt not issued"); // c: no receipt yet
    }

    @Test
    void ignoresFullyRefundedDonations() {
        assertThat(Form10bd.lines(Form10bd.register(List.of(row("x", "Meera", "ABCDE1234F", "ONLINE", "0.00")), new Data.ReceiptFacts(Map.of(), List.of())))).isEmpty();
    }
}

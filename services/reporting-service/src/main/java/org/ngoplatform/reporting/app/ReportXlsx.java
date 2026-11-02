package org.ngoplatform.reporting.app;

import java.io.ByteArrayOutputStream;
import java.io.IOException;
import java.math.BigDecimal;
import java.time.LocalDate;
import java.util.List;
import java.util.function.Function;
import org.dhatim.fastexcel.Workbook;
import org.dhatim.fastexcel.Worksheet;

/** Excel workbooks: the NGO-wide report, and the 80G register with Form 10BD data. Amounts are numbers (no symbols). */
public final class ReportXlsx {
    private ReportXlsx() {}

    private static final String MONEY = "#,##0.00";
    private static final String DATE = "dd-mmm-yyyy";

    public static byte[] ngo(Reports.NgoReport r) {
        return book(wb -> {
            Worksheet s = wb.newWorksheet("Summary");
            int row = header(s, "NGO report", r.ngoName(), r.period().label(), r);
            Reports.Donations d = r.donations();
            row = amount(s, row, "Total received (net of refunds)", d.total());
            row = count(s, row, "Donations", d.count());
            row = count(s, row, "Donors", d.donors());
            if (r.donors() != null && r.donors().newDonors() != null) row = count(s, row, "New donors in this period", r.donors().newDonors());
            if (r.pending() != null) {
                row = count(s, row, "Expense claims waiting for approval", r.pending().expenses());
                row = amount(s, row, "Amount of claims waiting", r.pending().expenseAmount());
                count(s, row, "Campaigns waiting for approval", r.pending().campaigns());
            }
            s.width(0, 40); s.width(1, 18);

            rows(wb.newWorksheet("By month"), "Month", d.byMonth(), Labels::month);
            rows(wb.newWorksheet("By mode"), "Mode", d.byChannel(), Labels::channel);
            rows(wb.newWorksheet("By campaign"), "Campaign", d.byCampaign(), k -> null);
            if (r.donors() != null) rows(wb.newWorksheet("Top donors"), "Donor", r.donors().top(), k -> null);
            if (r.campaigns() != null) {
                Worksheet c = wb.newWorksheet("Campaigns");
                titles(c, "Campaign", "Status", "Target", "Raised", "Donations", "Budget", "Spent (approved)");
                int i = 1;
                for (Data.CampaignRow x : r.campaigns()) {
                    c.value(i, 0, x.title()); c.value(i, 1, Labels.campaignStatus(x.status()));
                    num(c, i, 2, x.goal()); num(c, i, 3, x.raised()); c.value(i, 4, x.donations()); num(c, i, 5, x.planned()); num(c, i, 6, x.spent());
                    i++;
                }
                c.width(0, 40); for (int k = 1; k <= 6; k++) c.width(k, 16);
            }
            if (r.staffCash() != null) {
                Worksheet c = wb.newWorksheet("Staff cash");
                titles(c, "Member", "Advanced", "Spent", "Returned", "Reimbursed", "Holds", "NGO owes");
                int i = 1;
                for (Reports.StaffCash x : r.staffCash()) {
                    c.value(i, 0, x.name());
                    num(c, i, 1, x.advanced()); num(c, i, 2, x.spent()); num(c, i, 3, x.returned()); num(c, i, 4, x.reimbursed());
                    num(c, i, 5, x.holds()); num(c, i, 6, x.owed());
                    i++;
                }
                c.width(0, 32); for (int k = 1; k <= 6; k++) c.width(k, 14);
            }
        });
    }

    public static byte[] tax(Reports.TaxReport r) {
        return book(wb -> {
            Worksheet b = wb.newWorksheet("Form 10BD");
            titles(b, "Sr. No.", "ID Code", "Unique Identification Number", "Section Code", "Name of donor", "Address of donor",
                    "Donation Type", "Mode of receipt", "Amount of donation (Indian rupees)", "Donations", "Receipt numbers", "Check before filing");
            int i = 1;
            for (Form10bd.Line l : r.form10bd()) {
                b.value(i, 0, l.sr()); b.value(i, 1, l.idCode()); b.value(i, 2, l.idNumber()); b.value(i, 3, l.section());
                b.value(i, 4, l.name()); b.value(i, 5, l.address()); b.value(i, 6, l.donationType()); b.value(i, 7, l.mode());
                num(b, i, 8, l.amount()); b.value(i, 9, l.donations()); b.value(i, 10, l.receiptNumbers()); b.value(i, 11, l.note());
                i++;
            }
            b.width(2, 16); b.width(4, 28); b.width(5, 40); b.width(7, 30); b.width(8, 18); b.width(10, 26); b.width(11, 44);

            Worksheet g = wb.newWorksheet("80G register");
            titles(g, "Date", "Receipt number", "Donor", "PAN", "Address", "Mode", "Amount", "Campaign");
            i = 1;
            for (Form10bd.RegisterLine l : r.register()) {
                g.value(i, 0, LocalDate.ofInstant(l.paidAt(), Period.IST)); g.style(i, 0).format(DATE).set();
                g.value(i, 1, l.receiptNumber()); g.value(i, 2, l.donorName()); g.value(i, 3, l.pan()); g.value(i, 4, l.address());
                g.value(i, 5, l.mode()); num(g, i, 6, l.amount()); g.value(i, 7, l.campaignTitle());
                i++;
            }
            g.value(i, 5, "Total"); num(g, i, 6, r.total()); g.range(i, 5, i, 6).style().bold().set();
            g.width(0, 14); g.width(1, 18); g.width(2, 28); g.width(3, 14); g.width(4, 40); g.width(5, 30); g.width(6, 14); g.width(7, 30);

            Worksheet n = wb.newWorksheet("About");
            n.value(0, 0, "80G register and Form 10BD data - " + r.ngoName());
            n.value(1, 0, "Period: " + r.period().label() + ". Generated " + ReportPdf.STAMP.format(r.generatedAt()) + " with Yogdaan.");
            n.value(2, 0, "Yogdaan does not file Form 10BD. Your accountant should check every line (see 'Check before filing')");
            n.value(3, 0, "and copy it into the Form 10BD template on the income-tax portal. Donation type is 'Others' unless you change it.");
            n.width(0, 110);
        });
    }

    private interface Body { void write(Workbook wb) throws IOException; }

    private static byte[] book(Body body) {
        ByteArrayOutputStream out = new ByteArrayOutputStream();
        try {
            Workbook wb = new Workbook(out, "Yogdaan", "1.0");
            body.write(wb);
            wb.finish();
        } catch (IOException e) {
            throw new IllegalStateException("Report workbook failed", e);
        }
        return out.toByteArray();
    }

    private static int header(Worksheet s, String title, String ngo, String period, Reports.NgoReport r) {
        s.value(0, 0, title); s.style(0, 0).bold().fontSize(14).set();
        s.value(1, 0, ngo);
        s.value(2, 0, "Period: " + period);
        s.value(3, 0, "Generated " + ReportPdf.STAMP.format(r.generatedAt()) + " with Yogdaan");
        return 5;
    }

    private static void titles(Worksheet ws, String... t) {
        for (int c = 0; c < t.length; c++) ws.value(0, c, t[c]);
        ws.range(0, 0, 0, t.length - 1).style().bold().fillColor("E8F3EC").set();
    }

    private static void rows(Worksheet ws, String first, List<Data.Row> rows, Function<String, String> label) {
        titles(ws, first, "Donations", "Amount");
        int i = 1;
        for (Data.Row x : rows) {
            String l = label.apply(x.key());
            ws.value(i, 0, l != null ? l : x.label() != null ? x.label() : "General (no campaign)");
            ws.value(i, 1, x.count());
            num(ws, i, 2, x.amount());
            i++;
        }
        ws.width(0, 36); ws.width(1, 12); ws.width(2, 16);
    }

    private static int amount(Worksheet ws, int r, String k, BigDecimal v) {
        ws.value(r, 0, k);
        num(ws, r, 1, v);
        return r + 1;
    }

    private static int count(Worksheet ws, int r, String k, long v) {
        ws.value(r, 0, k);
        ws.value(r, 1, v);
        return r + 1;
    }

    private static void num(Worksheet ws, int r, int c, BigDecimal v) {
        ws.value(r, c, v == null ? BigDecimal.ZERO : v);
        ws.style(r, c).format(MONEY).set();
    }
}

package org.ngoplatform.campaign.app;

import java.io.ByteArrayOutputStream;
import java.io.IOException;
import java.math.BigDecimal;
import org.dhatim.fastexcel.Workbook;
import org.dhatim.fastexcel.Worksheet;

/**
 * Excel workbook of {@link AccountSheet.Sheet} for the NGO's accountant: Summary, Expenses (one row per voucher, real
 * numbers and dates so they can be summed / filtered), Pending &amp; void, Staff cash.
 */
public final class AccountSheetXlsx {
    private static final String MONEY = "#,##0.00";
    private static final String DATE = "dd-mmm-yyyy";

    private AccountSheetXlsx() {}

    public static byte[] render(AccountSheet.Sheet s) {
        ByteArrayOutputStream out = new ByteArrayOutputStream();
        try {
            Workbook wb = new Workbook(out, "Seva Setu", "1.0");
            summary(wb.newWorksheet("Summary"), s);
            expenses(wb.newWorksheet("Expenses"), s);
            others(wb.newWorksheet("Pending and void"), s);
            staff(wb.newWorksheet("Staff cash"), s);
            wb.finish();
        } catch (IOException e) {
            throw new IllegalStateException("Account sheet workbook failed", e);
        }
        return out.toByteArray();
    }

    private static void summary(Worksheet ws, AccountSheet.Sheet s) {
        int r = 0;
        title(ws, r++, "Campaign account sheet" + (s.finalSheet() ? " (FINAL)" : " (PROVISIONAL)"));
        r = pair(ws, r, "NGO", s.ngoName());
        r = pair(ws, r, "Campaign", s.campaign().title());
        r = pair(ws, r, "Period", AccountSheetPdf.DATE.format(s.campaign().startDate()) + " to "
                + (s.campaign().endDate() == null ? "open" : AccountSheetPdf.DATE.format(s.campaign().endDate())));
        r = pair(ws, r, "Status", AccountSheet.statusLabel(s.campaign().status()));
        r = pair(ws, r, "Generated", AccountSheetPdf.STAMP.format(s.generatedAt()) + (s.generatedBy() == null ? "" : " by " + s.generatedBy()));
        r++;
        AccountSheet.Income in = s.income();
        AccountSheet.Totals t = s.totals();
        r = amount(ws, r, "Goal", s.campaign().goal(), false);
        r = amount(ws, r, "Online donations (" + in.onlineCount() + ")", in.online(), false);
        if (in.testCount() > 0) r = amount(ws, r, "Test donations (" + in.testCount() + ")", in.test(), false);
        if (in.refunded().signum() > 0) r = amount(ws, r, "Refunded", in.refunded().negate(), false);
        r = amount(ws, r, "Money received", in.net(), true);
        r = amount(ws, r, "Spent (approved expenses)", t.spent(), true);
        r = amount(ws, r, "Balance", t.balance(), true);
        r = amount(ws, r, "Awaiting approval (" + t.pendingCount() + ", not counted)", t.pendingAmount(), false);
        r++;
        header(ws, r++, "Category", "Planned", "Spent", "Remaining", "Items");
        for (AccountSheet.BudgetRow b : s.budget()) {
            ws.value(r, 0, b.category() + (b.budgeted() ? "" : " (not in budget)"));
            if (b.budgeted()) { num(ws, r, 1, b.planned()); num(ws, r, 3, b.remaining()); }
            num(ws, r, 2, b.spent());
            ws.value(r, 4, b.count());
            r++;
        }
        ws.value(r, 0, "Total");
        num(ws, r, 1, t.planned());
        num(ws, r, 2, t.spent());
        num(ws, r, 3, t.budgetRemaining());
        ws.value(r, 4, t.approvedCount());
        ws.range(r, 0, r, 4).style().bold().set();
        ws.width(0, 44);
        for (int c = 1; c <= 4; c++) ws.width(c, 16);
    }

    private static void expenses(Worksheet ws, AccountSheet.Sheet s) {
        header(ws, 0, "Voucher", "Date", "Category", "Description", "Paid to", "Bill no.", "Mode", "Paid by", "Amount",
                "Submitted by", "Approved by", "Bills attached", "No-bill reason");
        int r = 1;
        for (AccountSheet.ExpenseRow e : s.expenses()) {
            ws.value(r, 0, e.voucherNo());
            ws.value(r, 1, e.date());
            ws.style(r, 1).format(DATE).set();
            ws.value(r, 2, e.category());
            ws.value(r, 3, e.description());
            ws.value(r, 4, e.vendor());
            ws.value(r, 5, e.billNumber());
            ws.value(r, 6, AccountSheet.modeLabel(e.mode()));
            ws.value(r, 7, e.paidBy());
            num(ws, r, 8, e.amount());
            ws.value(r, 9, e.submittedBy());
            ws.value(r, 10, e.decidedBy() == null ? null : e.decidedBy() + (e.selfApproved() ? " (self)" : ""));
            ws.value(r, 11, e.bills());
            ws.value(r, 12, e.noBillReason());
            r++;
        }
        ws.value(r, 7, "Total");
        num(ws, r, 8, s.totals().spent());
        ws.range(r, 7, r, 8).style().bold().set();
        int[] widths = {18, 13, 16, 40, 24, 12, 13, 20, 14, 20, 20, 8, 30};
        for (int c = 0; c < widths.length; c++) ws.width(c, widths[c]);
        ws.freezePane(0, 1);
    }

    private static void others(Worksheet ws, AccountSheet.Sheet s) {
        header(ws, 0, "Status", "Voucher", "Date", "Category", "Description", "Paid to", "Amount", "Submitted by", "Void reason");
        int r = 1;
        for (var list : java.util.List.of(s.pending(), s.voided())) {
            for (AccountSheet.ExpenseRow e : list) {
                ws.value(r, 0, "SUBMITTED".equals(e.status()) ? "Awaiting approval" : "Void");
                ws.value(r, 1, e.voucherNo());
                ws.value(r, 2, e.date());
                ws.style(r, 2).format(DATE).set();
                ws.value(r, 3, e.category());
                ws.value(r, 4, e.description());
                ws.value(r, 5, e.vendor());
                num(ws, r, 6, e.amount());
                ws.value(r, 7, e.submittedBy());
                ws.value(r, 8, e.voidReason());
                r++;
            }
        }
        int[] widths = {18, 18, 13, 16, 40, 24, 14, 20, 30};
        for (int c = 0; c < widths.length; c++) ws.width(c, widths[c]);
    }

    private static void staff(Worksheet ws, AccountSheet.Sheet s) {
        header(ws, 0, "Staff member", "Advance given", "Spent for NGO", "Returned", "Reimbursed", "Balance (+ NGO owes, - staff holds)");
        int r = 1;
        for (AccountSheet.StaffRow x : s.staff()) {
            ws.value(r, 0, x.name());
            num(ws, r, 1, x.advanced());
            num(ws, r, 2, x.spent());
            num(ws, r, 3, x.returned());
            num(ws, r, 4, x.reimbursed());
            num(ws, r, 5, x.balance());
            r++;
        }
        ws.width(0, 28);
        for (int c = 1; c <= 4; c++) ws.width(c, 16);
        ws.width(5, 34);
    }

    private static void title(Worksheet ws, int r, String t) {
        ws.value(r, 0, t);
        ws.style(r, 0).bold().fontSize(14).set();
    }

    private static int pair(Worksheet ws, int r, String k, String v) {
        ws.value(r, 0, k);
        ws.value(r, 1, v);
        ws.style(r, 0).bold().set();
        return r + 1;
    }

    private static int amount(Worksheet ws, int r, String k, BigDecimal v, boolean bold) {
        ws.value(r, 0, k);
        num(ws, r, 1, v);
        if (bold) ws.range(r, 0, r, 1).style().bold().set();
        return r + 1;
    }

    private static void header(Worksheet ws, int r, String... cols) {
        for (int c = 0; c < cols.length; c++) ws.value(r, c, cols[c]);
        ws.range(r, 0, r, cols.length - 1).style().bold().fillColor("E6F3EC").set();
    }

    private static void num(Worksheet ws, int r, int c, BigDecimal v) {
        ws.value(r, c, v);
        ws.style(r, c).format(MONEY).set();
    }
}

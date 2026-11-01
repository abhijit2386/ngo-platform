package org.ngoplatform.campaign.api;

import com.fasterxml.jackson.databind.ObjectMapper;
import jakarta.validation.Valid;
import jakarta.validation.constraints.*;
import java.io.IOException;
import java.math.BigDecimal;
import java.nio.charset.StandardCharsets;
import java.time.Instant;
import java.time.LocalDate;
import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.UUID;
import org.ngoplatform.campaign.app.AccountRules;
import org.ngoplatform.campaign.app.AccountSheet;
import org.ngoplatform.campaign.app.AccountSheetPdf;
import org.ngoplatform.campaign.app.AccountSheetXlsx;
import org.ngoplatform.campaign.app.AccountsService;
import org.ngoplatform.campaign.app.CampaignService;
import org.ngoplatform.campaign.app.Csv;
import org.ngoplatform.campaign.domain.Campaign;
import org.ngoplatform.campaign.domain.Expense;
import org.ngoplatform.campaign.domain.Vendor;
import org.ngoplatform.commons.error.ApiException;
import org.ngoplatform.commons.security.AccessGuard;
import org.ngoplatform.commons.security.Membership;
import org.ngoplatform.commons.security.Permissions;
import org.springframework.http.ContentDisposition;
import org.springframework.http.HttpHeaders;
import org.springframework.http.HttpStatus;
import org.springframework.http.MediaType;
import org.springframework.http.ResponseEntity;
import org.springframework.http.CacheControl;
import org.springframework.web.bind.annotation.*;
import org.springframework.web.multipart.MultipartFile;

/**
 * Campaign accounts (owner request 2026-10-29): budget, expenses with bills + approval, vendors, staff cash ledger and
 * the account sheet (JSON / PDF / Excel). Team members need EXPENSE_VIEW to see and EXPENSE_RECORD to add;
 * administrators approve (checked in {@link AccountRules}).
 */
@RestController
public class AccountsController {
    public record ExpenseRequest(UUID budgetLineId, @Size(max = 60) String category,
                                 @NotBlank @Size(max = 300) String description,
                                 @NotNull @Digits(integer = 10, fraction = 2) BigDecimal amount,
                                 @NotNull LocalDate date, UUID vendorId, @Size(max = 120) String vendorName,
                                 @Size(max = 40) String billNumber, @Size(max = 200) String noBillReason,
                                 @NotBlank String paymentMode, @NotBlank String paidBy, UUID paidByUser, Long version) {
        AccountsService.ExpenseInput input() {
            return new AccountsService.ExpenseInput(budgetLineId, category, description, amount, date, vendorId, vendorName,
                    billNumber, noBillReason, paymentMode, paidBy, paidByUser);
        }
    }

    public record ExpenseView(UUID id, UUID campaignId, UUID budgetLineId, String category, String description, BigDecimal amount,
                              LocalDate date, UUID vendorId, String vendorName, String billNumber, String noBillReason,
                              String paymentMode, String paidBy, UUID paidByUser, String status, String voucherNo,
                              UUID submittedBy, Instant submittedAt, UUID decidedBy, Instant decidedAt, String rejectionReason,
                              boolean selfApproved, UUID voidedBy, Instant voidedAt, String voidReason, long version,
                              List<AttachmentView> attachments, int attachmentCount, String campaignTitle) {
        static ExpenseView of(Expense e, List<AttachmentView> atts, int count, String campaignTitle) {
            return new ExpenseView(e.getId(), e.getCampaignId(), e.getBudgetLineId(), e.getCategory(), e.getDescription(), e.getAmount(),
                    e.getExpenseDate(), e.getVendorId(), e.getVendorName(), e.getBillNumber(), e.getNoBillReason(), e.getPaymentMode(),
                    e.getPaidBy(), e.getPaidByUser(), e.getStatus().name(), e.getVoucherNo(), e.getSubmittedBy(), e.getSubmittedAt(),
                    e.getDecidedBy(), e.getDecidedAt(), e.getRejectionReason(), e.isSelfApproved(), e.getVoidedBy(), e.getVoidedAt(),
                    e.getVoidReason(), e.getVersion(), atts, count, campaignTitle);
        }
    }

    public record AttachmentView(UUID id, String contentType, long size, String fileName, UUID uploadedBy, Instant createdAt) {
        static AttachmentView of(AccountsService.Attachment a) {
            return new AttachmentView(a.id(), a.contentType(), a.size(), a.fileName(), a.uploadedBy(), a.createdAt());
        }
    }

    public record BudgetLineRequest(UUID id, @NotBlank @Size(max = 60) String category,
                                    @NotNull @Digits(integer = 11, fraction = 2) BigDecimal planned) {}
    public record BudgetRequest(@NotNull @Size(max = 30) List<@Valid BudgetLineRequest> lines) {}
    public record VersionRequest(@NotNull Long version) {}
    public record ReasonRequest(@NotBlank @Size(min = 5, max = 500) String reason) {}

    public record VendorRequest(@NotBlank @Size(max = 120) String name, @Size(max = 20) String phone, @Size(max = 120) String email,
                                @Size(max = 20) String gstin, @Size(max = 12) String pan, @Size(max = 300) String address,
                                @Size(max = 60) String category, @Size(max = 500) String notes, Boolean active, Long version) {
        AccountRules.VendorInput input() { return new AccountRules.VendorInput(name, phone, email, gstin, pan, address, category, notes); }
    }

    public record VendorView(UUID id, String name, String phone, String email, String gstin, String pan, String address,
                             String category, String notes, boolean active, long version) {
        static VendorView of(Vendor v) {
            return new VendorView(v.getId(), v.getName(), v.getPhone(), v.getEmail(), v.getGstin(), v.getPan(), v.getAddress(),
                    v.getCategory(), v.getNotes(), v.isActive(), v.getVersion());
        }
    }

    public record MoneyRequest(@NotNull UUID userId, @NotBlank @Pattern(regexp = "ADVANCE|RETURN|REIMBURSEMENT") String kind,
                               @NotNull @Digits(integer = 10, fraction = 2) BigDecimal amount, @NotBlank String mode,
                               LocalDate date, UUID campaignId, @Size(max = 300) String note) {}

    private final AccountsService svc;
    private final AccountSheet sheets;
    private final CampaignService campaigns;
    private final AccessGuard guard;
    private final ObjectMapper json;

    public AccountsController(AccountsService svc, AccountSheet sheets, CampaignService campaigns, AccessGuard guard, ObjectMapper json) {
        this.svc = svc; this.sheets = sheets; this.campaigns = campaigns; this.guard = guard; this.json = json;
    }

    private static final String BASE = "/api/v1/ngos/{ngoId}/campaigns/{cid}";

    // ---------------- budget

    @GetMapping(BASE + "/budget")
    public Map<String, Object> budget(@PathVariable UUID ngoId, @PathVariable UUID cid) {
        guard.require(ngoId, Permissions.CAMPAIGN_VIEW);
        campaigns.load(ngoId, cid);
        return Map.of("lines", svc.budget(cid), "suggestedCategories", AccountRules.DEFAULT_CATEGORIES);
    }

    @PutMapping(BASE + "/budget")
    public Map<String, Object> setBudget(@PathVariable UUID ngoId, @PathVariable UUID cid, @Valid @RequestBody BudgetRequest req) {
        Membership m = guard.require(ngoId, Permissions.CAMPAIGN_MANAGE);
        List<AccountsService.BudgetLineInput> lines = req.lines().stream()
                .map(l -> new AccountsService.BudgetLineInput(l.id(), l.category(), l.planned())).toList();
        return Map.of("lines", svc.setBudget(ngoId, m, cid, lines), "suggestedCategories", AccountRules.DEFAULT_CATEGORIES);
    }

    // ---------------- expenses

    @GetMapping(BASE + "/expenses")
    public List<ExpenseView> expenses(@PathVariable UUID ngoId, @PathVariable UUID cid, @RequestParam(required = false) AccountRules.Status status) {
        guard.require(ngoId, Permissions.EXPENSE_VIEW);
        Campaign c = campaigns.load(ngoId, cid);
        Map<UUID, Integer> counts = svc.attachmentCounts(cid);
        return svc.campaignExpenses(c.getId()).stream().filter(e -> status == null || e.getStatus() == status)
                .map(e -> ExpenseView.of(e, null, counts.getOrDefault(e.getId(), 0), null)).toList();
    }

    /** Create with bills in one request: part "expense" (JSON) + up to 5 parts "files" (JPEG/PNG/PDF). */
    @PostMapping(value = BASE + "/expenses", consumes = MediaType.MULTIPART_FORM_DATA_VALUE)
    @ResponseStatus(HttpStatus.CREATED)
    public ExpenseView create(@PathVariable UUID ngoId, @PathVariable UUID cid, @RequestPart("expense") String body,
                              @RequestPart(value = "files", required = false) List<MultipartFile> files) throws IOException {
        Membership m = guard.require(ngoId, Permissions.EXPENSE_RECORD);
        ExpenseRequest req = parse(body);
        List<AccountsService.Upload> uploads = new ArrayList<>();
        if (files != null) for (MultipartFile f : files) if (!f.isEmpty()) uploads.add(new AccountsService.Upload(f.getBytes(), f.getOriginalFilename()));
        return view(svc.create(ngoId, m, cid, req.input(), uploads));
    }

    /** JSON-only create (no bills yet must come with a no-bill reason). */
    @PostMapping(value = BASE + "/expenses", consumes = MediaType.APPLICATION_JSON_VALUE)
    @ResponseStatus(HttpStatus.CREATED)
    public ExpenseView createJson(@PathVariable UUID ngoId, @PathVariable UUID cid, @Valid @RequestBody ExpenseRequest req) {
        Membership m = guard.require(ngoId, Permissions.EXPENSE_RECORD);
        return view(svc.create(ngoId, m, cid, req.input(), List.of()));
    }

    @GetMapping(BASE + "/expenses/{id}")
    public ExpenseView expense(@PathVariable UUID ngoId, @PathVariable UUID cid, @PathVariable UUID id) {
        guard.require(ngoId, Permissions.EXPENSE_VIEW);
        return view(svc.load(ngoId, cid, id));
    }

    @PutMapping(BASE + "/expenses/{id}")
    public ExpenseView update(@PathVariable UUID ngoId, @PathVariable UUID cid, @PathVariable UUID id, @Valid @RequestBody ExpenseRequest req) {
        Membership m = guard.require(ngoId, Permissions.EXPENSE_RECORD);
        if (req.version() == null) throw ApiException.badRequest("VERSION_REQUIRED", "version is required");
        return view(svc.update(ngoId, m, cid, id, req.input(), req.version()));
    }

    @DeleteMapping(BASE + "/expenses/{id}")
    @ResponseStatus(HttpStatus.NO_CONTENT)
    public void withdraw(@PathVariable UUID ngoId, @PathVariable UUID cid, @PathVariable UUID id) {
        Membership m = guard.require(ngoId, Permissions.EXPENSE_RECORD);
        svc.withdraw(ngoId, m, cid, id);
    }

    @PostMapping(BASE + "/expenses/{id}/approve")
    public ExpenseView approve(@PathVariable UUID ngoId, @PathVariable UUID cid, @PathVariable UUID id, @Valid @RequestBody VersionRequest req) {
        Membership m = guard.require(ngoId, Permissions.EXPENSE_APPROVE);
        return view(svc.approve(ngoId, m, cid, id, req.version()));
    }

    @PostMapping(BASE + "/expenses/{id}/reject")
    public ExpenseView reject(@PathVariable UUID ngoId, @PathVariable UUID cid, @PathVariable UUID id, @Valid @RequestBody ReasonRequest req) {
        Membership m = guard.require(ngoId, Permissions.EXPENSE_APPROVE);
        return view(svc.reject(ngoId, m, cid, id, req.reason()));
    }

    @PostMapping(BASE + "/expenses/{id}/void")
    public ExpenseView voidExpense(@PathVariable UUID ngoId, @PathVariable UUID cid, @PathVariable UUID id, @Valid @RequestBody ReasonRequest req) {
        Membership m = guard.require(ngoId, Permissions.EXPENSE_APPROVE);
        return view(svc.voidExpense(ngoId, m, cid, id, req.reason()));
    }

    @PostMapping(value = BASE + "/expenses/{id}/attachments", consumes = MediaType.MULTIPART_FORM_DATA_VALUE)
    @ResponseStatus(HttpStatus.CREATED)
    public ExpenseView addAttachment(@PathVariable UUID ngoId, @PathVariable UUID cid, @PathVariable UUID id,
                                     @RequestPart("file") MultipartFile file) throws IOException {
        Membership m = guard.require(ngoId, Permissions.EXPENSE_RECORD);
        svc.addAttachment(ngoId, m, cid, id, new AccountsService.Upload(file.getBytes(), file.getOriginalFilename()));
        return view(svc.load(ngoId, cid, id));
    }

    @DeleteMapping(BASE + "/expenses/{id}/attachments/{attId}")
    public ExpenseView removeAttachment(@PathVariable UUID ngoId, @PathVariable UUID cid, @PathVariable UUID id, @PathVariable UUID attId) {
        Membership m = guard.require(ngoId, Permissions.EXPENSE_RECORD);
        svc.removeAttachment(ngoId, m, cid, id, attId);
        return view(svc.load(ngoId, cid, id));
    }

    @GetMapping(BASE + "/expenses/{id}/attachments/{attId}")
    public ResponseEntity<byte[]> attachment(@PathVariable UUID ngoId, @PathVariable UUID cid, @PathVariable UUID id, @PathVariable UUID attId) {
        guard.require(ngoId, Permissions.EXPENSE_VIEW);
        svc.load(ngoId, cid, id);
        AccountsService.Attachment a = svc.attachment(id, attId);
        return ResponseEntity.ok().cacheControl(CacheControl.maxAge(java.time.Duration.ofDays(30)).cachePrivate())
                .header("X-Content-Type-Options", "nosniff")
                .header(HttpHeaders.CONTENT_DISPOSITION, ContentDisposition.inline()
                        .filename(a.fileName() == null ? "bill" + ("application/pdf".equals(a.contentType()) ? ".pdf" : ".jpg") : a.fileName(), StandardCharsets.UTF_8)
                        .build().toString())
                .contentType(MediaType.parseMediaType(a.contentType())).body(svc.read(a.key()));
    }

    /** NGO-wide approval inbox (administrators) and the count for the home screen. */
    @GetMapping("/api/v1/ngos/{ngoId}/expenses/pending")
    public Map<String, Object> pending(@PathVariable UUID ngoId) {
        guard.require(ngoId, Permissions.EXPENSE_VIEW);
        List<Expense> list = svc.pending(ngoId);
        Map<UUID, String> titles = new LinkedHashMap<>();
        List<ExpenseView> views = list.stream().map(e -> ExpenseView.of(e, null, 0,
                titles.computeIfAbsent(e.getCampaignId(), id -> campaigns.load(ngoId, id).getTitle()))).toList();
        BigDecimal total = list.stream().map(Expense::getAmount).reduce(BigDecimal.ZERO, BigDecimal::add);
        return Map.of("count", list.size(), "total", total, "items", views);
    }

    // ---------------- account sheet

    @GetMapping(BASE + "/accounts")
    public AccountSheet.Sheet accounts(@PathVariable UUID ngoId, @PathVariable UUID cid) {
        Membership m = guard.require(ngoId, Permissions.EXPENSE_VIEW);
        return sheets.build(campaigns.load(ngoId, cid), m.userId());
    }

    @GetMapping(BASE + "/accounts.pdf")
    public ResponseEntity<byte[]> accountsPdf(@PathVariable UUID ngoId, @PathVariable UUID cid) {
        Membership m = guard.require(ngoId, Permissions.EXPENSE_VIEW);
        Campaign c = campaigns.load(ngoId, cid);
        return download(AccountSheetPdf.render(sheets.build(c, m.userId())), MediaType.APPLICATION_PDF, fileName(c, "pdf"));
    }

    @GetMapping(BASE + "/accounts.xlsx")
    public ResponseEntity<byte[]> accountsXlsx(@PathVariable UUID ngoId, @PathVariable UUID cid) {
        Membership m = guard.require(ngoId, Permissions.EXPENSE_VIEW);
        Campaign c = campaigns.load(ngoId, cid);
        return download(AccountSheetXlsx.render(sheets.build(c, m.userId())),
                MediaType.parseMediaType("application/vnd.openxmlformats-officedocument.spreadsheetml.sheet"), fileName(c, "xlsx"));
    }

    private static String fileName(Campaign c, String ext) {
        return "account-sheet-" + c.getSlug() + "-" + java.time.LocalDate.now(java.time.ZoneId.of("Asia/Kolkata")) + "." + ext;
    }

    private static ResponseEntity<byte[]> download(byte[] body, MediaType type, String name) {
        return ResponseEntity.ok().cacheControl(CacheControl.noStore())
                .header(HttpHeaders.CONTENT_DISPOSITION, ContentDisposition.attachment().filename(name, StandardCharsets.UTF_8).build().toString())
                .contentType(type).body(body);
    }

    // ---------------- staff money

    @GetMapping("/api/v1/ngos/{ngoId}/staff-money")
    public List<AccountsService.StaffBalance> balances(@PathVariable UUID ngoId) {
        guard.requireAdmin(ngoId);
        return svc.balances(ngoId, null);
    }

    /** One member's ledger: administrators see anyone's, members their own. */
    @GetMapping("/api/v1/ngos/{ngoId}/staff-money/{userId}")
    public Map<String, Object> ledger(@PathVariable UUID ngoId, @PathVariable String userId) {
        Membership m = guard.member(ngoId);
        UUID who = "me".equals(userId) ? m.userId() : UUID.fromString(userId);
        if (!who.equals(m.userId()) && !m.isAdmin()) throw ApiException.forbidden("ADMIN_REQUIRED", "You can see only your own advances");
        return Map.of("userId", who, "balance", svc.balance(ngoId, who), "entries", svc.entries(ngoId, who));
    }

    @PostMapping("/api/v1/ngos/{ngoId}/staff-money")
    @ResponseStatus(HttpStatus.CREATED)
    public AccountsService.MoneyEntry record(@PathVariable UUID ngoId, @Valid @RequestBody MoneyRequest req) {
        Membership m = guard.require(ngoId, Permissions.EXPENSE_APPROVE);
        return svc.recordMoney(ngoId, m, req.userId(), req.kind(), req.amount(), req.mode(), req.date(), req.campaignId(), req.note());
    }

    // ---------------- vendors

    @GetMapping("/api/v1/ngos/{ngoId}/vendors")
    public List<VendorView> vendors(@PathVariable UUID ngoId, @RequestParam(required = false) String q,
                                    @RequestParam(defaultValue = "false") boolean includeInactive) {
        guard.require(ngoId, Permissions.EXPENSE_VIEW);
        String needle = q == null ? null : q.strip().toLowerCase();
        return svc.vendors(ngoId).stream().filter(v -> includeInactive || v.isActive())
                .filter(v -> needle == null || needle.isEmpty() || v.getName().toLowerCase().contains(needle)
                        || (v.getPhone() != null && v.getPhone().contains(needle)) || (v.getCategory() != null && v.getCategory().toLowerCase().contains(needle)))
                .map(VendorView::of).toList();
    }

    @PostMapping("/api/v1/ngos/{ngoId}/vendors")
    @ResponseStatus(HttpStatus.CREATED)
    public VendorView createVendor(@PathVariable UUID ngoId, @Valid @RequestBody VendorRequest req) {
        Membership m = guard.require(ngoId, Permissions.EXPENSE_RECORD);
        return VendorView.of(svc.createVendor(ngoId, m.userId(), req.input()));
    }

    @GetMapping("/api/v1/ngos/{ngoId}/vendors/{id}")
    public VendorView vendor(@PathVariable UUID ngoId, @PathVariable UUID id) {
        guard.require(ngoId, Permissions.EXPENSE_VIEW);
        return VendorView.of(svc.vendor(ngoId, id));
    }

    @PutMapping("/api/v1/ngos/{ngoId}/vendors/{id}")
    public VendorView updateVendor(@PathVariable UUID ngoId, @PathVariable UUID id, @Valid @RequestBody VendorRequest req) {
        Membership m = guard.require(ngoId, Permissions.EXPENSE_RECORD);
        if (req.version() == null) throw ApiException.badRequest("VERSION_REQUIRED", "version is required");
        return VendorView.of(svc.updateVendor(ngoId, m.userId(), id, req.input(), req.active(), req.version()));
    }

    @PostMapping(value = "/api/v1/ngos/{ngoId}/vendors/import", consumes = MediaType.MULTIPART_FORM_DATA_VALUE)
    public AccountsService.ImportResult importVendors(@PathVariable UUID ngoId, @RequestPart("file") MultipartFile file) throws IOException {
        Membership m = guard.require(ngoId, Permissions.EXPENSE_RECORD);
        return svc.importVendors(ngoId, m.userId(), file.getBytes());
    }

    /** Empty CSV with the expected header and one example row, to fill in Excel / Google Sheets. */
    @GetMapping("/api/v1/ngos/{ngoId}/vendors/template.csv")
    public ResponseEntity<byte[]> vendorTemplate(@PathVariable UUID ngoId) {
        guard.require(ngoId, Permissions.EXPENSE_VIEW);
        String csv = AccountsService.VENDOR_CSV_HEADER + "\r\n"
                + String.join(",", Csv.field("Sharma Stationers"), "9876543210", "sharma@example.com", "27ABCDE1234F1Z5", "ABCDE1234F",
                Csv.field("12 MG Road, Pune"), "Stationery", Csv.field("Notebooks and pens")) + "\r\n";
        return download(csv.getBytes(StandardCharsets.UTF_8), MediaType.parseMediaType("text/csv"), "vendors-template.csv");
    }

    /** Vendor list as CSV (same columns as the import). */
    @GetMapping("/api/v1/ngos/{ngoId}/vendors/export.csv")
    public ResponseEntity<byte[]> vendorExport(@PathVariable UUID ngoId) {
        guard.require(ngoId, Permissions.EXPENSE_VIEW);
        StringBuilder b = new StringBuilder(AccountsService.VENDOR_CSV_HEADER).append(",active\r\n");
        for (Vendor v : svc.vendors(ngoId)) {
            b.append(String.join(",", Csv.field(v.getName()), Csv.field(v.getPhone()), Csv.field(v.getEmail()), Csv.field(v.getGstin()),
                    Csv.field(v.getPan()), Csv.field(v.getAddress()), Csv.field(v.getCategory()), Csv.field(v.getNotes()),
                    v.isActive() ? "yes" : "no")).append("\r\n");
        }
        byte[] bom = {(byte) 0xEF, (byte) 0xBB, (byte) 0xBF}; // Excel opens UTF-8 (Hindi names) correctly with a BOM
        byte[] body = b.toString().getBytes(StandardCharsets.UTF_8);
        byte[] out = new byte[bom.length + body.length];
        System.arraycopy(bom, 0, out, 0, bom.length);
        System.arraycopy(body, 0, out, bom.length, body.length);
        return download(out, MediaType.parseMediaType("text/csv"), "vendors.csv");
    }

    // ---------------- helpers

    private ExpenseView view(Expense e) {
        List<AttachmentView> atts = svc.attachments(e.getId()).stream().map(AttachmentView::of).toList();
        return ExpenseView.of(e, atts, atts.size(), null);
    }

    private ExpenseRequest parse(String body) {
        try {
            return json.readValue(body, ExpenseRequest.class);
        } catch (IOException e) {
            throw ApiException.badRequest("INVALID_EXPENSE", "The expense data could not be read");
        }
    }
}

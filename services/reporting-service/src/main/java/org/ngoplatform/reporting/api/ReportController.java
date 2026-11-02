package org.ngoplatform.reporting.api;

import java.nio.charset.StandardCharsets;
import java.time.LocalDate;
import java.util.Map;
import java.util.UUID;
import org.ngoplatform.reporting.app.Period;
import org.ngoplatform.reporting.app.ReportPdf;
import org.ngoplatform.reporting.app.ReportService;
import org.ngoplatform.reporting.app.ReportXlsx;
import org.ngoplatform.reporting.app.Reports;
import org.springframework.format.annotation.DateTimeFormat;
import org.springframework.http.CacheControl;
import org.springframework.http.ContentDisposition;
import org.springframework.http.HttpHeaders;
import org.springframework.http.MediaType;
import org.springframework.http.ResponseEntity;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.RequestParam;
import org.springframework.web.bind.annotation.RestController;

/**
 * Reports for NGO teams. Dates are India-time days, both included; without dates, the current financial year.
 * Access rules: {@link org.ngoplatform.reporting.app.Access}.
 */
@RestController
public class ReportController {
    private static final MediaType XLSX = MediaType.parseMediaType("application/vnd.openxmlformats-officedocument.spreadsheetml.sheet");

    private final ReportService reports;

    public ReportController(ReportService reports) { this.reports = reports; }

    /** Reports tab: NGO-wide tiles for administrators and Finance, "my" tiles for everyone else with report access. */
    @GetMapping("/api/v1/ngos/{ngoId}/dashboard")
    public Map<String, Object> dashboard(@PathVariable UUID ngoId) {
        return reports.dashboard(ngoId);
    }

    @GetMapping("/api/v1/ngos/{ngoId}/reports/summary")
    public Reports.NgoReport summary(@PathVariable UUID ngoId, @RequestParam(required = false) @DateTimeFormat(iso = DateTimeFormat.ISO.DATE) LocalDate from,
                                     @RequestParam(required = false) @DateTimeFormat(iso = DateTimeFormat.ISO.DATE) LocalDate to) {
        return reports.ngoReport(ngoId, reports.period(from, to));
    }

    @GetMapping("/api/v1/ngos/{ngoId}/reports/summary.pdf")
    public ResponseEntity<byte[]> summaryPdf(@PathVariable UUID ngoId, @RequestParam(required = false) @DateTimeFormat(iso = DateTimeFormat.ISO.DATE) LocalDate from,
                                             @RequestParam(required = false) @DateTimeFormat(iso = DateTimeFormat.ISO.DATE) LocalDate to) {
        Period p = reports.period(from, to);
        return file(ReportPdf.render(reports.ngoReportForExport(ngoId, p, "PDF")), MediaType.APPLICATION_PDF, "ngo-report-" + slug(p) + ".pdf");
    }

    @GetMapping("/api/v1/ngos/{ngoId}/reports/summary.xlsx")
    public ResponseEntity<byte[]> summaryXlsx(@PathVariable UUID ngoId, @RequestParam(required = false) @DateTimeFormat(iso = DateTimeFormat.ISO.DATE) LocalDate from,
                                              @RequestParam(required = false) @DateTimeFormat(iso = DateTimeFormat.ISO.DATE) LocalDate to) {
        Period p = reports.period(from, to);
        return file(ReportXlsx.ngo(reports.ngoReportForExport(ngoId, p, "XLSX")), XLSX, "ngo-report-" + slug(p) + ".xlsx");
    }

    @GetMapping("/api/v1/ngos/{ngoId}/reports/me")
    public Reports.MyReport me(@PathVariable UUID ngoId, @RequestParam(required = false) @DateTimeFormat(iso = DateTimeFormat.ISO.DATE) LocalDate from,
                               @RequestParam(required = false) @DateTimeFormat(iso = DateTimeFormat.ISO.DATE) LocalDate to) {
        return reports.myReport(ngoId, reports.period(from, to));
    }

    @GetMapping("/api/v1/ngos/{ngoId}/reports/80g")
    public Reports.TaxReport tax(@PathVariable UUID ngoId, @RequestParam(required = false) @DateTimeFormat(iso = DateTimeFormat.ISO.DATE) LocalDate from,
                                 @RequestParam(required = false) @DateTimeFormat(iso = DateTimeFormat.ISO.DATE) LocalDate to) {
        return reports.taxReport(ngoId, reports.period(from, to), false);
    }

    @GetMapping("/api/v1/ngos/{ngoId}/reports/80g.xlsx")
    public ResponseEntity<byte[]> taxXlsx(@PathVariable UUID ngoId, @RequestParam(required = false) @DateTimeFormat(iso = DateTimeFormat.ISO.DATE) LocalDate from,
                                          @RequestParam(required = false) @DateTimeFormat(iso = DateTimeFormat.ISO.DATE) LocalDate to) {
        Period p = reports.period(from, to);
        return file(ReportXlsx.tax(reports.taxReport(ngoId, p, true)), XLSX, "80g-register-form-10bd-" + slug(p) + ".xlsx");
    }

    private static String slug(Period p) { return p.label().replace("FY ", "fy-").replace(' ', '-').toLowerCase(); }

    private static ResponseEntity<byte[]> file(byte[] body, MediaType type, String name) {
        return ResponseEntity.ok().cacheControl(CacheControl.noStore())
                .header(HttpHeaders.CONTENT_DISPOSITION, ContentDisposition.attachment().filename(name, StandardCharsets.UTF_8).build().toString())
                .contentType(type).body(body);
    }
}

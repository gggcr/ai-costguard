package ai.costguard.core.cabinet.internal;

import ai.costguard.core.shared.security.TenantClaim;
import io.swagger.v3.oas.annotations.Operation;
import io.swagger.v3.oas.annotations.tags.Tag;
import jakarta.validation.Valid;
import java.io.IOException;
import java.io.UncheckedIOException;
import java.time.YearMonth;
import java.util.List;
import org.springframework.http.MediaType;
import org.springframework.security.access.prepost.PreAuthorize;
import org.springframework.security.core.annotation.AuthenticationPrincipal;
import org.springframework.security.oauth2.jwt.Jwt;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.PutMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RequestParam;
import org.springframework.web.bind.annotation.RequestPart;
import org.springframework.web.bind.annotation.RestController;
import org.springframework.web.multipart.MultipartFile;

/** Зарплата (ADR 0054). Pay is read only by the employee and by owner and finance; it_admin and auditor do not. */
@RestController
@RequestMapping("/api/v1")
@Tag(name = "Cabinet", description = "The employee's own cabinet: only the person of the token")
class PayrollController {

    private final PayrollService service;

    PayrollController(PayrollService service) {
        this.service = service;
    }

    @GetMapping("/me/payroll")
    @Operation(summary = "My pay: the pay days ahead and my payments of the last two years")
    MyPayroll mine(@AuthenticationPrincipal Jwt jwt) {
        return service.mine(TenantClaim.of(jwt), jwt);
    }

    @GetMapping("/payroll/schedule")
    @PreAuthorize("hasAnyRole('owner', 'finance')")
    @Operation(summary = "The days of the month the company pays the advance and the salary")
    PayrollSchedule schedule(@AuthenticationPrincipal Jwt jwt) {
        return service.schedule(TenantClaim.of(jwt));
    }

    @PutMapping("/payroll/schedule")
    @PreAuthorize("hasAnyRole('owner', 'finance')")
    @Operation(summary = "Set the pay days")
    PayrollSchedule saveSchedule(@AuthenticationPrincipal Jwt jwt, @Valid @RequestBody PayrollSchedule schedule) {
        return service.saveSchedule(TenantClaim.of(jwt), schedule);
    }

    @GetMapping("/payroll/payments")
    @PreAuthorize("hasAnyRole('owner', 'finance')")
    @Operation(summary = "Payments of a month (yyyy-MM), every employee")
    List<PayrollPayment> ofMonth(@AuthenticationPrincipal Jwt jwt, @RequestParam String month) {
        return service.ofMonth(TenantClaim.of(jwt), YearMonth.parse(month));
    }

    @PostMapping("/payroll/payments")
    @PreAuthorize("hasAnyRole('owner', 'finance', 'connector')")
    @Operation(summary = "Payments from a source such as the 1С:ЗУП connector; the same payment again replaces it")
    PayrollImport store(@AuthenticationPrincipal Jwt jwt, @Valid @RequestBody PaymentBatch batch) {
        return service.store(TenantClaim.of(jwt), batch);
    }

    @PostMapping(path = "/payroll/imports", consumes = MediaType.MULTIPART_FORM_DATA_VALUE)
    @PreAuthorize("hasAnyRole('owner', 'finance')")
    @Operation(summary = "A pay sheet, .xlsx or .csv: сотрудник, вид, период, дата выплаты, сумма")
    PayrollImport load(@AuthenticationPrincipal Jwt jwt, @RequestPart("file") MultipartFile file) {
        try {
            return service.load(TenantClaim.of(jwt), file.getOriginalFilename() == null ? "payroll.csv"
                    : file.getOriginalFilename(), file.getBytes());
        } catch (IOException e) {
            throw new UncheckedIOException(e);
        }
    }
}

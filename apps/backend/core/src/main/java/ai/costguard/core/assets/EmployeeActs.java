package ai.costguard.core.assets;

import java.time.LocalDate;
import java.util.List;
import java.util.Optional;
import java.util.UUID;

/** The handover acts of one employee, for «Мой кабинет». Call inside a transaction already bound to the tenant. */
public interface EmployeeActs {

    List<Act> actsOf(UUID employeeId);

    /** The PDF of one act, only when the act belongs to this employee. */
    Optional<ActFile> file(UUID employeeId, UUID actId);

    /** {@code returnedOn} is empty while the employee still holds the device. */
    record Act(UUID id, String number, LocalDate issuedOn, String model, String inventoryNumber,
            LocalDate returnDue, LocalDate returnedOn) {
    }

    record ActFile(String number, byte[] pdf, String sha256) {
    }
}

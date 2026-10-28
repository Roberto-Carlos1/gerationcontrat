package mg.pacte.model;

import jakarta.validation.Valid;
import com.fasterxml.jackson.annotation.JsonInclude;
import mg.pacte.ai.AiModels;
import jakarta.validation.constraints.*;
import java.math.BigDecimal;
import java.time.LocalDate;
import java.util.List;
import java.util.Map;

/** Le format JSON reste compatible avec les sauvegardes du frontend. */
public final class Registry {
    private Registry() {}

    public record Contract(
            @NotBlank @Pattern(regexp = "CT-[a-zA-Z0-9-]+") @Size(max = 200) String id,
            @NotBlank @Size(max = 150) String name,
            @NotBlank @Size(max = 120) String supplier,
            @NotBlank @Size(max = 200) String category,
            @DecimalMin("0") @DecimalMax("1000000000000") @Digits(integer = 13, fraction = 2) BigDecimal amount,
            @NotNull @Pattern(regexp = "Mensuel|Trimestriel|Annuel") String frequency,
            @NotNull LocalDate start,
            LocalDate end,
            @NotNull @Min(0) @Max(730) Integer notice,
            @NotNull @Pattern(regexp = "Oui|Non") String renew,
            @NotBlank @Size(max = 200) String owner,
            @NotNull @Pattern(regexp = "Actif|Brouillon|Terminé|Résilié") String status,
            @NotNull @Size(max = 10000) String penalty,
            @NotNull @Size(max = 10000) String notes,
            @Valid Details details) {
        public Contract(String id, String name, String supplier, String category, BigDecimal amount,
                String frequency, LocalDate start, LocalDate end, Integer notice, String renew,
                String owner, String status, String penalty, String notes) {
            this(id,name,supplier,category,amount,frequency,start,end,notice,renew,owner,status,penalty,notes,null);
        }
        public Contract withStatus(String value) {
            return new Contract(id, name, supplier, category, amount, frequency, start, end,
                    notice, renew, owner, value, penalty, notes, details);
        }
    }

    public record Details(
            @NotBlank @Size(max=120) String reference,
            @NotBlank @Pattern(regexp="distribution|commercial|partenariat|approvisionnement|services|autre") String type,
            @NotNull @Size(max=200) String representative,
            @NotBlank @Size(max=200) String department,
            @NotBlank @Size(max=10000) String purpose,
            LocalDate signed,
            @NotBlank @Pattern(regexp="[A-Z]{3}") String currency,
            @NotNull @Size(max=10000) String payment,
            @NotNull @Size(max=10000) String clauses,
            @NotNull @Size(max=20) Map<@NotBlank @Size(max=50) String, @NotNull @Size(max=10000) String> specific) {}

    public record Dispute(
            @NotBlank @Pattern(regexp = "LT-[a-zA-Z0-9-]+") @Size(max = 200) String id,
            @NotBlank @Size(max = 200) String contract,
            @NotBlank @Size(max = 10000) String title,
            @NotBlank @Size(max = 10000) String description,
            @NotNull @DecimalMin("0") @DecimalMax("1000000000000") @Digits(integer = 13, fraction = 2) BigDecimal amount,
            @NotBlank @Size(max = 200) String owner,
            @NotNull @Min(0) @Max(3) Integer stage,
            @NotEmpty @Size(max = 10000) List<@NotBlank @Size(max = 10000) String> history) {}

    public record ClauseGroup(
            @NotBlank @Size(max = 200) String label,
            @NotNull @Size(max = 10000) List<@NotNull @Size(max = 10000) String> excerpts) {}

    public record Analysis(
            @NotBlank @Size(max = 200) String contract,
            @NotBlank @Size(max = 200) String date,
            @NotNull @Size(max = 100) List<@NotNull @Valid ClauseGroup> groups,
            @NotNull Boolean validated,
            @Valid @JsonInclude(JsonInclude.Include.NON_NULL) AiModels.Details ai) {
        public Analysis(String contract, String date, List<ClauseGroup> groups, Boolean validated) {
            this(contract,date,groups,validated,null);
        }
    }

    public record Data(
            @NotNull @Size(max = 10000) List<@NotNull @Valid Contract> contracts,
            @NotNull @Size(max = 10000) List<@NotNull @Valid Dispute> cases,
            @NotNull @Size(max = 10000) List<@NotNull @Pattern(regexp = "CT-[a-zA-Z0-9-]+:\\d{4}-\\d{2}-\\d{2}:(10|30)") String> done,
            @NotNull @Size(max = 10000) List<@NotNull @Valid Analysis> analysis) {}

    public record Snapshot(@NotNull @Min(0) Long revision, @NotNull @Valid Data data) {}
}

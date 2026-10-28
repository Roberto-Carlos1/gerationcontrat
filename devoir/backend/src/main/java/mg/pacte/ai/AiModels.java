package mg.pacte.ai;

import jakarta.validation.Valid;
import jakarta.validation.constraints.*;
import java.util.List;
import mg.pacte.model.Registry.ClauseGroup;

public final class AiModels {
    private AiModels() {}
    public record Request(
            @NotBlank @Pattern(regexp="CT-[a-zA-Z0-9-]+") String contractId,
            @NotBlank @Size(max=40000) String text) {}

    public record Risk(
            @NotBlank @Size(max=200) String title,
            @NotNull @Pattern(regexp="Bas|Modéré|Élevé|Indéterminé") String severity,
            @NotBlank @Size(max=2000) String explanation,
            @NotNull @Size(max=2000) String excerpt) {}

    public record Output(
            @NotBlank @Size(max=4000) String summary,
            @NotNull @Pattern(regexp="Bas|Modéré|Élevé|Indéterminé") String riskLevel,
            @NotNull @Size(min=5,max=5) List<@NotNull @Valid ClauseGroup> groups,
            @NotNull @Size(max=12) List<@NotNull @Valid Risk> risks,
            @NotNull @Size(max=12) List<@NotBlank @Size(max=2000) String> recommendations) {}

    public record Details(
            @NotBlank @Size(max=200) String provider,
            @NotBlank @Size(max=200) String model,
            @NotBlank @Size(max=4000) String summary,
            @NotNull @Pattern(regexp="Bas|Modéré|Élevé|Indéterminé") String riskLevel,
            @NotNull @Size(max=12) List<@NotNull @Valid Risk> risks,
            @NotNull @Size(max=12) List<@NotBlank @Size(max=2000) String> recommendations) {}

    public record Status(boolean configured, String provider, String model, int maxCharacters) {}
}

package dev.sast.contracts;

import jakarta.validation.constraints.DecimalMax;
import jakarta.validation.constraints.DecimalMin;
import jakarta.validation.constraints.NotNull;

public record AiTriage(
        @NotNull Verdict verdict,
        @NotNull @DecimalMin("0") @DecimalMax("1") Double confidence,
        String reason,
        String suggestedFix) {

    public enum Verdict { likely_tp, likely_fp }
}

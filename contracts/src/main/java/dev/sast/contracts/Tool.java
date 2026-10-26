package dev.sast.contracts;

import jakarta.validation.constraints.NotBlank;

public record Tool(@NotBlank String name, String version) {
}

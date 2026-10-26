package dev.sast.contracts;

public record AiTriage(Verdict verdict, double confidence, String reason, String suggestedFix) {

    public enum Verdict { likely_tp, likely_fp }
}

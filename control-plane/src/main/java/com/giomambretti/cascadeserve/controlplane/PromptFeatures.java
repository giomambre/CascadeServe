package com.giomambretti.cascadeserve.controlplane;

public record PromptFeatures(
        int wordCount,
        int charCount,
        int digitCount,
        int symbolCount,
        int newlineCount) {
    public static PromptFeatures from(String prompt) {
        String trimmed = prompt.trim();
        int words = trimmed.isEmpty() ? 0 : trimmed.split("\\s+").length;
        int digits = 0;
        int symbols = 0;
        int newlines = 0;
        for (int index = 0; index < prompt.length(); index++) {
            char value = prompt.charAt(index);
            if (Character.isDigit(value)) {
                digits++;
            }
            if ("{}[]()=+-*/".indexOf(value) >= 0) {
                symbols++;
            }
            if (value == '\n') {
                newlines++;
            }
        }
        return new PromptFeatures(words, prompt.length(), digits, symbols, newlines);
    }
}

package io.github.krait4g.radarexplorer.service;

import io.github.krait4g.radarexplorer.service.ViewerExceptions.BadRequest;

import java.time.LocalDateTime;
import java.time.format.DateTimeFormatter;
import java.time.format.DateTimeParseException;
import java.time.format.ResolverStyle;
import java.util.Locale;
import java.util.Set;

final class CompactEventTimeParser {

    private static final Set<Integer> SUPPORTED_LENGTHS = Set.of(8, 10, 12, 14, 17);
    private static final DateTimeFormatter FORMATTER = DateTimeFormatter
            .ofPattern("uuuuMMddHHmmssSSS", Locale.ROOT)
            .withResolverStyle(ResolverStyle.STRICT);

    private CompactEventTimeParser() {
    }

    static ParsedEventTime parse(String parameterName, String value) {
        if (value == null || !SUPPORTED_LENGTHS.contains(value.length()) || !isAsciiDigits(value)) {
            throw invalid(parameterName);
        }

        String normalized = value + "0".repeat(17 - value.length());
        try {
            return new ParsedEventTime(value, normalized, LocalDateTime.parse(normalized, FORMATTER));
        } catch (DateTimeParseException exception) {
            throw invalid(parameterName);
        }
    }

    static String format(LocalDateTime value) {
        return FORMATTER.format(value);
    }

    private static boolean isAsciiDigits(String value) {
        for (int index = 0; index < value.length(); index++) {
            char current = value.charAt(index);
            if (current < '0' || current > '9') {
                return false;
            }
        }
        return true;
    }

    private static BadRequest invalid(String parameterName) {
        return new BadRequest(
                "INVALID_OBSERVED_TIME",
                parameterName + " must be a valid yyyyMMdd, yyyyMMddHH, yyyyMMddHHmm, "
                        + "yyyyMMddHHmmss, or yyyyMMddHHmmssSSS value."
        );
    }

    record ParsedEventTime(String requested, String normalized, LocalDateTime value) {
    }
}

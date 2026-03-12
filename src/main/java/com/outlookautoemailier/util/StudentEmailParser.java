package com.outlookautoemailier.util;

import java.util.Optional;
import java.util.regex.Matcher;
import java.util.regex.Pattern;

/**
 * Parses student email addresses of the form {2-digit year}{letters major}{digits id}@domain
 * e.g. 25comp1019@isik.edu.tr → year=25, major="comp", studentId="1019"
 */
public final class StudentEmailParser {

    private static final Pattern STUDENT_PATTERN =
            Pattern.compile("^(\\d{2})([A-Za-z]{4})(\\d{4})@isik\\.edu\\.tr$", Pattern.CASE_INSENSITIVE);

    public record ParsedStudentEmail(int year, String major, String studentId) {}

    public static Optional<ParsedStudentEmail> parse(String email) {
        if (email == null || email.isBlank()) return Optional.empty();
        Matcher m = STUDENT_PATTERN.matcher(email.trim());
        if (!m.matches()) return Optional.empty();
        return Optional.of(new ParsedStudentEmail(
                Integer.parseInt(m.group(1)),
                m.group(2).toLowerCase(),
                m.group(3)
        ));
    }

    private StudentEmailParser() {}
}

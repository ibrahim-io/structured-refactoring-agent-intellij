package com.example.utils;

import java.time.LocalDate;
import java.time.format.DateTimeFormatter;

/**
 * Date utility class used by benchmark tasks.
 * - move-001 moves this class to package 'com.example.common'
 */
public class DateHelper {

    private static final DateTimeFormatter DEFAULT_FORMAT = DateTimeFormatter.ISO_LOCAL_DATE;

    public static String format(LocalDate date) {
        return date.format(DEFAULT_FORMAT);
    }

    public static LocalDate parse(String dateStr) {
        return LocalDate.parse(dateStr, DEFAULT_FORMAT);
    }
}

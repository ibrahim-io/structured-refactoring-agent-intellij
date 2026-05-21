package com.example;

/**
 * Legacy utility class used by benchmark tasks.
 * - safe-delete-001 deletes the unused method 'parseOldFormat'
 */
public class LegacyHelper {

    /** Unused — benchmark task safe-deletes this. */
    public static String parseOldFormat(String input) {
        return input.trim().toLowerCase();
    }

    public static String parseNewFormat(String input) {
        return input.trim();
    }
}

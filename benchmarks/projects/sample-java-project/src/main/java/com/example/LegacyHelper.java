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

    /**
     * Inline target — benchmark task inline-001 replaces every call with
     * 'value.toLowerCase().trim()' and removes this declaration.
     * A text-edit agent must manually substitute the parameter name at each
     * call site; InlineMethodProcessor does it correctly by construction.
     */
    public static String normalize(String value) {
        return value.toLowerCase().trim();
    }
}

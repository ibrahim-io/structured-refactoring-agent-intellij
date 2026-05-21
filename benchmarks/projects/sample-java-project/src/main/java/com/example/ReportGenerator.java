package com.example;

/**
 * Cross-file caller for benchmark inline-001.
 * Calls LegacyHelper.normalize with parameter name 'fieldValue'.
 */
public class ReportGenerator {

    public String formatField(String fieldValue) {
        return LegacyHelper.normalize(fieldValue);
    }

    public String formatTitle(String title) {
        return "[" + LegacyHelper.normalize(title) + "]";
    }
}

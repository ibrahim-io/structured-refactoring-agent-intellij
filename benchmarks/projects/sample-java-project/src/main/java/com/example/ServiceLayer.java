package com.example;

import com.example.utils.DateHelper;

import java.time.LocalDate;

/**
 * Cross-file caller used by multiple benchmark tasks.
 * - move-001: imports com.example.utils.DateHelper. After DateHelper moves
 *   to com.example.common, MoveClassesOrPackagesProcessor updates this import
 *   automatically. A text-edit agent may miss this file -> compile failure.
 * - rename-method-002: calls LegacyHelper.parseNewFormat. After rename to
 *   parseInput, ReferencesSearch + handleElementRename updates this call.
 *   A text-edit agent editing only LegacyHelper.java leaves this broken.
 * - inline-001: calls LegacyHelper.normalize. InlineMethodProcessor replaces
 *   the call with 'raw.toLowerCase().trim()' (correct parameter substitution).
 */
public class ServiceLayer {

    public String processInput(String raw) {
        return LegacyHelper.parseNewFormat(raw);
    }

    public String normalizeInput(String raw) {
        return LegacyHelper.normalize(raw);
    }

    public String processOrder(String orderId) {
        return "Order " + orderId + " on " + DateHelper.format(LocalDate.now());
    }
}

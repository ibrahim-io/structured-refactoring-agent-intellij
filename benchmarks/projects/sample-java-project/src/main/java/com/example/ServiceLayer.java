package com.example;

import com.example.utils.DateHelper;

import java.time.LocalDate;

/**
 * Cross-file caller used by benchmark tasks.
 * - rename-method-002: after LegacyHelper.parseNewFormat is renamed to
 *   parseInput, the structured agent's ReferencesSearch finds this call
 *   site and updates it via handleElementRename. A text-edit agent that
 *   only edits LegacyHelper.java leaves this call broken -> compile failure.
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

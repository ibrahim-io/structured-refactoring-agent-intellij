package com.example;

import com.example.utils.DateHelper;
import java.time.LocalDate;

/**
 * Cross-file import dependency used by benchmark tasks.
 * - move-001: after DateHelper moves to com.example.common, the structured
 *   agent's MoveClassesOrPackagesProcessor updates this import automatically.
 *   A text-edit agent leaves 'import com.example.utils.DateHelper' unchanged,
 *   causing a compilation failure.
 */
public class OrderProcessor {

    public String processOrder(String orderId) {
        String today = DateHelper.format(LocalDate.now());
        return "Order " + orderId + " processed on " + today;
    }
}

package com.example;

import java.util.List;
import java.util.ArrayList;

/**
 * Cross-file caller for benchmark inline-001.
 * Calls LegacyHelper.normalize with parameter name 'entry' inside a loop —
 * a common real-world pattern that requires correct parameter substitution.
 */
public class DataImporter {

    public List<String> importRecords(List<String> rawRecords) {
        List<String> result = new ArrayList<>();
        for (String entry : rawRecords) {
            result.add(LegacyHelper.normalize(entry));
        }
        return result;
    }
}

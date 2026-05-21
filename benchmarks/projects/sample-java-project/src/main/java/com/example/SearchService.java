package com.example;

/**
 * Cross-file caller for benchmark inline-001.
 * Calls LegacyHelper.normalize with parameter name 'query' — distinct
 * from ServiceLayer which uses 'raw'.  InlineMethodProcessor correctly
 * substitutes the parameter at each call site; a text-edit agent that
 * copies the body verbatim produces the wrong variable name.
 */
public class SearchService {

    public String search(String query) {
        String normalized = LegacyHelper.normalize(query);
        return "Results for: " + normalized;
    }
}

package com.wanderingjew.gedcomanalyzer;

import java.io.IOException;

/**
 * Thrown when Geni's API returns HTTP 403 for a specific profile — typically a
 * privacy restriction (e.g. a living person's profile the requesting app isn't
 * permitted to view), not a token problem. Distinct from other {@link IOException}s
 * so callers can skip just that one profile and keep going, rather than aborting
 * the whole fetch — 403s are expected to occur repeatedly on a deep descendant
 * fetch that reaches many living relatives' profiles.
 */
public class GeniAccessDeniedException extends IOException {
    public GeniAccessDeniedException(String message) {
        super(message);
    }
}

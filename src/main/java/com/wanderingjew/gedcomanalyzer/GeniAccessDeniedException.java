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
    private final boolean fromCache;

    public GeniAccessDeniedException(String message, boolean fromCache) {
        super(message);
        this.fromCache = fromCache;
    }

    /**
     * True if this denial was already known from a prior run (a cached {@code _denied}
     * marker), false if it's a fresh 403 from a live call this run — i.e. a denial
     * we're seeing for the first time, whether because it's newly added on Geni or our
     * traversal simply hadn't reached it before.
     */
    public boolean isFromCache() {
        return fromCache;
    }
}

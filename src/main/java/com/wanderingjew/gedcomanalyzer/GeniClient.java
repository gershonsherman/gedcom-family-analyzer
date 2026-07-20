package com.wanderingjew.gedcomanalyzer;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;

import java.io.IOException;
import java.net.URI;
import java.net.URLEncoder;
import java.net.http.HttpClient;
import java.net.http.HttpRequest;
import java.net.http.HttpResponse;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.time.Duration;

/**
 * Thin client for the Geni REST API. Fetches a profile's immediate family,
 * caches each response on disk (so reruns and token refreshes are cheap), and
 * backs off politely when the API rate-limits us.
 */
public class GeniClient {

    private static final String API_BASE = "https://www.geni.com/api/";

    // Profile fields to request explicitly — the default immediate-family response
    // omits maiden_name and death. Bump CACHE_VERSION whenever this changes so stale
    // cached responses are refetched instead of reused.
    private static final String PROFILE_FIELDS =
            "id,guid,name,first_name,last_name,maiden_name,gender,is_alive,birth,death";
    private static final String CACHE_VERSION = "v2";

    private final HttpClient http = HttpClient.newBuilder()
            .followRedirects(HttpClient.Redirect.NORMAL)
            .connectTimeout(Duration.ofSeconds(30))
            .build();
    private final ObjectMapper mapper = new ObjectMapper();

    private final String accessToken;
    private final Path cacheDir;
    private final long requestDelayMs;

    // When true, never hit the network: serve from cache, return null on a miss.
    private boolean offline = false;

    private int requestCount = 0;
    private int cacheHits = 0;

    // Latest rate-limit state reported by the API (‑1 = unknown).
    private int rateLimit = -1;
    private int rateRemaining = -1;
    private int rateWindow = -1;
    private boolean rateReported = false;

    public GeniClient(String accessToken, Path cacheDir, long requestDelayMs) throws IOException {
        this.accessToken = accessToken;
        this.cacheDir = cacheDir;
        this.requestDelayMs = requestDelayMs;
        Files.createDirectories(cacheDir);
    }

    public int getRequestCount() { return requestCount; }
    public int getCacheHits() { return cacheHits; }

    /** Cache-only mode: serve from the cache, never call the network, return null on a miss. */
    public void setOffline(boolean offline) { this.offline = offline; }

    /**
     * Fetch the immediate family of a profile. {@code profileId} may be a numeric
     * id ("34670250082") or a guid form ("g6000000031619060876").
     */
    public JsonNode immediateFamily(String profileId) throws IOException, InterruptedException {
        Path cacheFile = cacheDir.resolve(profileId + "." + CACHE_VERSION + ".json");
        if (Files.exists(cacheFile)) {
            try {
                JsonNode node = mapper.readTree(Files.readAllBytes(cacheFile));
                cacheHits++;
                return node;
            } catch (IOException e) {
                // Likely a half-written file from a concurrent fetch. Skip it when offline;
                // when online, fall through and refetch a clean copy.
                if (offline) {
                    return null;
                }
            }
        }

        if (offline) {
            return null;
        }

        String body = get("profile-" + profileId + "/immediate-family");
        // Cache the raw JSON so subsequent runs skip the network entirely.
        Files.write(cacheFile, body.getBytes(StandardCharsets.UTF_8));
        return mapper.readTree(body);
    }

    private String get(String apiPath) throws IOException, InterruptedException {
        String url = API_BASE + apiPath
                + "?access_token=" + URLEncoder.encode(accessToken, StandardCharsets.UTF_8)
                + "&fields=" + URLEncoder.encode(PROFILE_FIELDS, StandardCharsets.UTF_8);

        int attempt = 0;
        while (true) {
            attempt++;
            pace();
            HttpRequest request = HttpRequest.newBuilder(URI.create(url))
                    .header("Accept", "application/json")
                    .timeout(Duration.ofSeconds(60))
                    .GET()
                    .build();
            HttpResponse<String> response = http.send(request, HttpResponse.BodyHandlers.ofString());
            requestCount++;
            readRateHeaders(response);
            int status = response.statusCode();

            if (status == 200) {
                return response.body();
            }
            if (status == 401) {
                throw new IOException("Geni API returned 401 Unauthorized — the access token is missing or expired. "
                        + "Get a fresh token and set GENI_ACCESS_TOKEN, then rerun (cached progress is kept).");
            }
            if ((status == 429 || status == 503) && attempt <= 12) {
                long waitMs = throttleWaitMillis(response, attempt);
                System.out.println("  Rate limited (HTTP " + status + ")"
                        + (rateWindow > 0 ? " [limit " + rateLimit + "/" + rateWindow + "s]" : "")
                        + "; waiting " + (waitMs / 1000) + "s (attempt " + attempt + ")...");
                Thread.sleep(waitMs);
                continue;
            }
            throw new IOException("Geni API request failed (HTTP " + status + ") for " + apiPath
                    + ": " + response.body());
        }
    }

    /** Proactively space requests to stay under the reported limit, and pause when the window is nearly spent. */
    private void pace() throws InterruptedException {
        // Minimum spacing derived from the reported limit (window / limit), with the
        // configured delay as a floor.
        long spacing = requestDelayMs;
        if (rateLimit > 0 && rateWindow > 0) {
            // +20% headroom so we don't ride exactly on the limit.
            long derived = (long) Math.ceil((rateWindow * 1000.0 / rateLimit) * 1.2);
            spacing = Math.max(spacing, derived);
        }
        if (spacing > 0) {
            Thread.sleep(spacing);
        }
        // If we've nearly exhausted the window, wait for it to reset.
        if (rateRemaining == 0 && rateWindow > 0) {
            Thread.sleep(rateWindow * 1000L);
        }
    }

    private void readRateHeaders(HttpResponse<String> response) {
        rateLimit = intHeader(response, "X-API-Rate-Limit", rateLimit);
        rateRemaining = intHeader(response, "X-API-Rate-Remaining", rateRemaining);
        rateWindow = intHeader(response, "X-API-Rate-Window", rateWindow);
        if (!rateReported && rateLimit > 0) {
            rateReported = true;
            System.out.println("Geni rate limit: " + rateLimit + " requests per "
                    + rateWindow + "s window.");
        }
    }

    private int intHeader(HttpResponse<String> response, String name, int fallback) {
        return response.headers().firstValue(name)
                .map(v -> {
                    try {
                        return Integer.parseInt(v.trim());
                    } catch (NumberFormatException e) {
                        return fallback;
                    }
                })
                .orElse(fallback);
    }

    private long throttleWaitMillis(HttpResponse<String> response, int attempt) {
        // Prefer Retry-After, then a full rate window, then exponential backoff (cap 120s).
        long retryAfter = response.headers().firstValue("Retry-After")
                .map(v -> {
                    try {
                        return Long.parseLong(v.trim()) * 1000L;
                    } catch (NumberFormatException e) {
                        return -1L;
                    }
                })
                .orElse(-1L);
        if (retryAfter > 0) {
            return retryAfter;
        }
        if (rateWindow > 0) {
            return rateWindow * 1000L;
        }
        return Math.min(120_000L, 1000L * (1L << Math.min(attempt, 7)));
    }
}

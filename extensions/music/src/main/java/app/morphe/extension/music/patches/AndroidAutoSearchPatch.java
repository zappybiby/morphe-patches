/*
 * Copyright 2026 Morphe.
 * https://github.com/MorpheApp/morphe-patches
 *
 * See the included NOTICE file for GPLv3 Section 7 terms that apply to this code.
 */

package app.morphe.extension.music.patches;

import static app.morphe.extension.music.patches.AndroidAutoPatch.BACKGROUND_EXECUTOR;
import static app.morphe.extension.music.patches.AndroidAutoPatch.createPlayableSearchResults;
import static app.morphe.extension.music.patches.AndroidAutoPatch.refreshHandler;
import static app.morphe.extension.music.patches.AndroidAutoPatch.transformFuture;

import android.support.v4.media.MediaBrowserCompat;

import androidx.annotation.Nullable;

import com.google.common.util.concurrent.ListenableFuture;

import java.lang.ref.WeakReference;
import java.util.ArrayList;
import java.util.Collections;
import java.util.List;
import java.util.Map;
import java.util.Objects;
import java.util.WeakHashMap;
import java.util.concurrent.CompletableFuture;
import java.util.concurrent.Executor;
import java.util.concurrent.atomic.AtomicBoolean;
import java.util.concurrent.atomic.AtomicReference;

import app.morphe.extension.shared.Logger;
import app.morphe.extension.shared.Utils;

/**
 * Replaces Android Auto's online search with the phone app's search across YouTube Music.
 * YTM's local Library search and caller checks stay in place.
 * Waits 250 ms before submitting a query so further typing can replace it.
 * Returns only results that have a playback action.
 */
@SuppressWarnings("unused")
public final class AndroidAutoSearchPatch {
    /** Added to YTM's phone search client to select catalog or uploaded music. */
    public interface PhoneSearchClient {
        /** Current account used to construct YTM's search requests. */
        Object patch_accountScope();
        ListenableFuture<PhoneSearchResponse> patch_search(String query, boolean uploaded,
                                                          @Nullable String params, Executor executor);
    }

    /** Added to YTM's phone search response to read its results as protobuf bytes. */
    public interface PhoneSearchResponse {
        byte[] patch_searchResponseBytes();
    }

    /** Added to YTM's Auto search request to read the query and return results. */
    public interface AndroidAutoSearchRequest {
        String patch_query();
        /** YTM reports null as a failed search; an empty list means success with no matches. */
        void patch_deliverSearchResults(@Nullable List<MediaBrowserCompat.MediaItem> playableResults);
    }

    private static final long TYPING_DELAY_MS = 250;
    private static final long DEADLINE_MS = 8_000;
    @Nullable
    private static SearchLoad pendingSearch;
    private static final Map<AndroidAutoSearchRequest, SearchCompletion> searchCompletions = new WeakHashMap<>();
    private static volatile PhoneSearchClient phoneSearchClient;

    private AndroidAutoSearchPatch() {}

    /** Injection point. Saves YTM's search client when its Android Auto service starts. */
    public static void setPhoneSearchClient(PhoneSearchClient client) {
        phoneSearchClient = client;
    }

    /** Injection point. Gives YTM-handled searches a deadline so an offline request cannot keep loading indefinitely. */
    public static void onSearchRequest(AndroidAutoSearchRequest request) {
        SearchLoad previous;
        synchronized (AndroidAutoSearchPatch.class) {
            previous = pendingSearch;
            if (previous != null && previous.key.query().equals(request.patch_query())) previous = null;
        }
        // Clearing the query can return through YTM's local-search path before handleSearch runs.
        if (previous != null) previous.finish(Collections.emptyList());
        synchronized (searchCompletions) {
            SearchCompletion completion = searchCompletions.computeIfAbsent(request, ignored -> new SearchCompletion());
            if (completion.delivered || completion.timeout != null) return;
            // A completed request may still receive a late native callback. Keep its completion state until YTM
            // releases it, without letting this timer or the map keep the request alive.
            WeakReference<AndroidAutoSearchRequest> reference = new WeakReference<>(request);
            completion.timeout = () -> {
                AndroidAutoSearchRequest pending = reference.get();
                if (pending == null) return;
                try { pending.patch_deliverSearchResults(null); }
                catch (Exception ex) { Logger.printException(() -> "Could not finish Android Auto search", ex); }
            };
            refreshHandler.postDelayed(completion.timeout, DEADLINE_MS);
        }
    }

    /** Injection point before native result delivery. Rejects late completion after a search has already finished. */
    public static boolean onSearchResult(AndroidAutoSearchRequest request) {
        synchronized (searchCompletions) {
            SearchCompletion completion = searchCompletions.computeIfAbsent(request, ignored -> new SearchCompletion());
            if (completion.delivered) return false;
            completion.delivered = true;
            completion.cancelDeadline();
            return true;
        }
    }

    private static final class SearchCompletion {
        boolean delivered;
        Runnable timeout;

        void cancelDeadline() {
            if (timeout != null) refreshHandler.removeCallbacks(timeout);
            timeout = null;
        }
    }

    // Search submission and typing delay

    /**
     * Injection point after YTM's caller and local-search checks. Queues a phone search after typing stops.
     *
     * @return true when the patch will deliver results; false to continue YTM's native search.
     */
    public static boolean handleSearch(AndroidAutoSearchRequest request) {
        PhoneSearchClient client = phoneSearchClient;
        if (client == null) return false;
        String query = request.patch_query();
        if (query == null || query.trim().isEmpty()) return false;
        synchronized (searchCompletions) {
            SearchCompletion completion = searchCompletions.get(request);
            if (completion != null) completion.cancelDeadline();
        }
        SearchKey key = searchKey(client, query);
        SearchLoad load;
        SearchLoad previous;
        synchronized (AndroidAutoSearchPatch.class) {
            previous = pendingSearch;
            if (previous != null && !previous.finished.get() && key.account() != null && previous.key.equals(key)) {
                // Repeated queries keep the original deadline so they cannot prolong loading indefinitely.
                previous.requests.add(request);
                return true;
            }
            pendingSearch = load = new SearchLoad(request, key);
        }
        if (previous != null) previous.finish(Collections.emptyList());
        refreshHandler.postDelayed(load.timeout, DEADLINE_MS);
        refreshHandler.postDelayed(load.submit, TYPING_DELAY_MS);
        return true;
    }

    /** Repeated queries share this load and its deadline; each caller completes once. */
    private static final class SearchLoad {
        final SearchKey key;
        final AtomicBoolean finished = new AtomicBoolean();
        volatile SearchResponses responses;
        final Runnable timeout = () -> {
            var pending = responses;
            try { finish(pending == null ? null : pending.availableResults()); }
            catch (Exception ex) {
                Logger.printException(() -> "Could not finish Android Auto search", ex);
                finish(null);
            }
        };
        final List<AndroidAutoSearchRequest> requests = new ArrayList<>();
        final Runnable submit;

        SearchLoad(AndroidAutoSearchRequest request, SearchKey key) {
            this.key = key;
            requests.add(request);
            submit = () -> BACKGROUND_EXECUTOR.execute(this::start);
        }

        void start() {
            if (finished.get()) return;
            try {
                if (!current(key)) { finish(Collections.emptyList()); return; }
                listenForResults(new SearchResponses(key.client(), key.query()));
            } catch (Exception ex) {
                Logger.printException(() -> "Could not request Android Auto search", ex);
                finish(null);
            }
        }

        void listenForResults(SearchResponses pending) {
            responses = pending;
            if (finished.get()) { pending.resultsFuture.cancel(true); return; }
            pending.resultsFuture.whenComplete((playableResults, error) -> {
                if (finished.get()) return;
                if (error != null) Logger.printException(() -> "Could not load Android Auto search results", error);
                finish(error == null ? playableResults : null);
            });
        }

        void finish(@Nullable List<MediaBrowserCompat.MediaItem> playableResults) {
            if (!finished.compareAndSet(false, true)) return;
            refreshHandler.removeCallbacks(timeout);
            refreshHandler.removeCallbacks(submit);
            synchronized (AndroidAutoSearchPatch.class) {
                if (pendingSearch == this) pendingSearch = null;
            }
            var pending = responses;
            if (pending != null && !pending.resultsFuture.isDone()) pending.resultsFuture.cancel(true);
            refreshHandler.post(() -> {
                try { deliver(playableResults); }
                catch (Exception ex) { Logger.printException(() -> "Could not deliver Android Auto search results", ex); }
            });
        }

        void deliver(@Nullable List<MediaBrowserCompat.MediaItem> playableResults) {
            // Account or service changes can happen while results wait on the main thread.
            if (!current(key)) playableResults = Collections.emptyList();
            for (AndroidAutoSearchRequest request : requests) {
                try { request.patch_deliverSearchResults(playableResults); }
                catch (Exception ex) { Logger.printException(() -> "Could not deliver Android Auto search results", ex); }
            }
        }
    }

    // Request scope and completion

    private record SearchKey(String query, PhoneSearchClient client, Object account, String locale) {
        @Override public boolean equals(Object other) {
            return other instanceof SearchKey key && query.equals(key.query) && client == key.client
                    && Objects.equals(account, key.account) && locale.equals(key.locale);
        }
        @Override public int hashCode() {
            return Objects.hash(query, System.identityHashCode(client), account, locale);
        }
    }

    private static SearchKey searchKey(PhoneSearchClient client, String query) {
        return new SearchKey(query, client, client.patch_accountScope(), Utils.getContext().getResources()
                .getConfiguration().getLocales().toLanguageTags());
    }

    private static boolean current(SearchKey key) {
        return key.client() == phoneSearchClient && key.equals(searchKey(key.client(), key.query()));
    }

    /** Keeps catalog or upload results when the other request fails or exceeds the Auto deadline. */
    private static final class SearchResponses {
        final CompletableFuture<List<AndroidAutoResponseParser.SearchResult>> catalog;
        final CompletableFuture<List<AndroidAutoResponseParser.SearchResult>> uploads;
        final CompletableFuture<List<MediaBrowserCompat.MediaItem>> resultsFuture;

        SearchResponses(PhoneSearchClient client, String query) {
            catalog = request(client, query, false);
            uploads = request(client, query, true);
            resultsFuture = CompletableFuture.allOf(catalog, uploads).handle((ignored, error) -> availableResults());
            resultsFuture.whenComplete((results, error) -> {
                if (resultsFuture.isCancelled()) {
                    catalog.cancel(true);
                    uploads.cancel(true);
                }
            });
        }

        private static CompletableFuture<List<AndroidAutoResponseParser.SearchResult>> request(
                PhoneSearchClient client, String query, boolean uploaded) {
            try {
                var navigation = transformFuture(
                        client.patch_search(query, uploaded, null, BACKGROUND_EXECUTOR),
                        PhoneSearchResponse::patch_searchResponseBytes, BACKGROUND_EXECUTOR);
                AtomicReference<CompletableFuture<List<AndroidAutoResponseParser.SearchResult>>> tabRequest =
                        new AtomicReference<>();
                AtomicBoolean cancelled = new AtomicBoolean();
                var results = navigation.thenCompose(response -> {
                    String params = uploaded ? AndroidAutoResponseParser.uploadedSearchParams(response) : "";
                    if (params.isEmpty()) return CompletableFuture.completedFuture(
                            AndroidAutoResponseParser.parseSearchResults(response, uploaded));
                    // The uploaded request type exposes the tab; its endpoint selects the account's uploads.
                    var tab = transformFuture(
                            client.patch_search(query, true, params, BACKGROUND_EXECUTOR),
                            page -> AndroidAutoResponseParser.parseSearchResults(page.patch_searchResponseBytes(), true),
                            BACKGROUND_EXECUTOR);
                    tabRequest.set(tab);
                    if (cancelled.get()) tab.cancel(true);
                    return tab;
                });
                results.whenComplete((value, error) -> {
                    if (!results.isCancelled()) return;
                    cancelled.set(true);
                    navigation.cancel(true);
                    var tab = tabRequest.get();
                    if (tab != null) tab.cancel(true);
                });
                return results;
            } catch (Exception ex) {
                Logger.printException(() -> "Could not request Android Auto search scope", ex);
                return CompletableFuture.completedFuture(null);
            }
        }

        @Nullable
        List<MediaBrowserCompat.MediaItem> availableResults() {
            List<AndroidAutoResponseParser.SearchResult> combined = new ArrayList<>();
            boolean completed = false;
            for (var response : List.of(catalog, uploads)) {
                if (!response.isDone() || response.isCancelled()) continue;
                try {
                    var results = response.getNow(null);
                    if (results == null) continue;
                    completed = true;
                    combined.addAll(results);
                } catch (Exception ex) {
                    Logger.printException(() -> "Could not load Android Auto search scope", ex);
                }
            }
            return completed ? createPlayableSearchResults(combined) : null;
        }
    }

}

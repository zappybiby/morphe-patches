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

import android.support.v4.media.MediaBrowserCompat;

import androidx.annotation.Nullable;

import com.google.common.util.concurrent.ListenableFuture;

import java.lang.ref.WeakReference;
import java.util.ArrayList;
import java.util.Collections;
import java.util.HashMap;
import java.util.List;
import java.util.Map;
import java.util.WeakHashMap;
import java.util.concurrent.CompletableFuture;
import java.util.concurrent.Executor;
import java.util.concurrent.atomic.AtomicBoolean;
import java.util.function.Function;
import java.util.function.Supplier;

import app.morphe.extension.shared.Logger;

/**
 * Replaces Android Auto's online search with the phone app's search across YouTube Music.
 * YTM's local Library search and caller checks stay in place.
 * Waits 250 ms before submitting a query so further typing can replace it.
 * Returns only results that have a playback action.
 */
@SuppressWarnings("unused")
public final class AndroidAutoSearchPatch {
    /** Added to YTM's phone search client to search its online music and podcast content. */
    public interface PhoneSearchClient {
        ListenableFuture<PhoneSearchResponse> patch_search(String query, Executor executor);
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
    private static final SharedSearchRequests SHARED_SEARCH_REQUESTS = new SharedSearchRequests();
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
        SearchLoad load = new SearchLoad(request, client, query);
        SearchLoad previous;
        synchronized (AndroidAutoSearchPatch.class) {
            previous = pendingSearch;
            if (previous != null && !previous.finished.get() && previous.query.equals(query)) {
                // Repeated queries join the queued search without extending its typing delay.
                previous.requests.add(request);
                return true;
            }
            pendingSearch = load;
        }
        if (previous != null) previous.finish(Collections.emptyList());
        refreshHandler.postDelayed(load.timeout, DEADLINE_MS);
        refreshHandler.postDelayed(load.submit, TYPING_DELAY_MS);
        return true;
    }

    /** Completes each search once, even when another search shares the phone request. */
    private static final class SearchLoad {
        final PhoneSearchClient client;
        final AtomicBoolean finished = new AtomicBoolean();
        volatile SharedSearchRequests.PendingSearchHandle pendingSearchHandle;
        final Runnable timeout = () -> finish(null);
        final List<AndroidAutoSearchRequest> requests = new ArrayList<>();
        final String query;
        final Runnable submit;

        SearchLoad(AndroidAutoSearchRequest request, PhoneSearchClient client, String query) {
            this.client = client;
            this.query = query;
            requests.add(request);
            submit = () -> {
                synchronized (AndroidAutoSearchPatch.class) {
                    if (pendingSearch == this) pendingSearch = null;
                }
                BACKGROUND_EXECUTOR.execute(this::start);
            };
        }

        void start() {
            if (finished.get()) return;
            try {
                listenForResults(requestSearchResults(client, query));
            } catch (Exception ex) {
                Logger.printException(() -> "Could not request Android Auto search", ex);
                finish(null);
            }
        }

        void listenForResults(SharedSearchRequests.PendingSearchHandle searchHandle) {
            pendingSearchHandle = searchHandle;
            if (finished.get()) { searchHandle.close(); return; }
            searchHandle.resultsFuture.whenComplete((playableResults, error) -> {
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
            var searchHandle = pendingSearchHandle;
            if (searchHandle != null) {
                if (!searchHandle.resultsFuture.isDone()) searchHandle.resultsFuture.cancel(true);
                searchHandle.close();
            }
            refreshHandler.post(() -> {
                try { deliver(playableResults); }
                catch (Exception ex) { Logger.printException(() -> "Could not deliver Android Auto search results", ex); }
            });
        }

        void deliver(@Nullable List<MediaBrowserCompat.MediaItem> playableResults) {
            for (AndroidAutoSearchRequest request : requests) {
                try { request.patch_deliverSearchResults(playableResults); }
                catch (Exception ex) { Logger.printException(() -> "Could not deliver Android Auto search results", ex); }
            }
        }
    }

    // Request sharing and cancellation

    private static SharedSearchRequests.PendingSearchHandle requestSearchResults(PhoneSearchClient client, String query) {
        return SHARED_SEARCH_REQUESTS.acquire(query,
                () -> SharedSearchRequests.transform(client.patch_search(query, BACKGROUND_EXECUTOR),
                        response -> createPlayableSearchResults(AndroidAutoResponseParser.parseSearchResults(
                                response.patch_searchResponseBytes())), BACKGROUND_EXECUTOR));
    }

    /** Auto can submit the same query again while it is still loading. Reuse the unfinished phone request. */
    private static final class SharedSearchRequests {
        private final Map<String, SharedSearch> pendingSearches = new HashMap<>();

        // Avoid Futures.transform: YTM's bundled Guava can throw NoSuchMethodError.
        static <T, R> CompletableFuture<R> transform(ListenableFuture<T> source,
                                                    Function<T, R> convert, Executor executor) {
            CompletableFuture<R> result = new CompletableFuture<>();
            result.whenComplete((value, error) -> {
                if (result.isCancelled()) source.cancel(true);
            });
            source.addListener(() -> {
                if (result.isDone()) return;
                try { result.complete(convert.apply(source.get())); }
                catch (Exception ex) { result.completeExceptionally(ex); }
            }, executor);
            return result;
        }

        private final class SharedSearch {
            final String query;
            final CompletableFuture<List<MediaBrowserCompat.MediaItem>> resultsFuture;
            int waitingSearchCount;

            SharedSearch(String query, CompletableFuture<List<MediaBrowserCompat.MediaItem>> resultsFuture) {
                this.query = query;
                this.resultsFuture = resultsFuture;
            }
        }

        final class PendingSearchHandle implements AutoCloseable {
            final CompletableFuture<List<MediaBrowserCompat.MediaItem>> resultsFuture;
            private SharedSearch sharedSearch;

            PendingSearchHandle(SharedSearch sharedSearch) {
                this.sharedSearch = sharedSearch;
                sharedSearch.waitingSearchCount++;
                // One search timing out must not cancel another search waiting for the same response.
                // Cancel the phone request only when no searches are waiting for it.
                resultsFuture = sharedSearch.resultsFuture.thenApply(Function.identity());
            }

            @Override
            public void close() {
                SharedSearch releasedSearch;
                synchronized (SharedSearchRequests.this) {
                    releasedSearch = sharedSearch;
                    if (releasedSearch == null) return;
                    sharedSearch = null;
                    if (--releasedSearch.waitingSearchCount != 0) return;
                    pendingSearches.remove(releasedSearch.query, releasedSearch);
                }
                if (!releasedSearch.resultsFuture.isDone()) releasedSearch.resultsFuture.cancel(true);
            }
        }

        synchronized PendingSearchHandle acquire(String query, Supplier<CompletableFuture<List<MediaBrowserCompat.MediaItem>>> start) {
            SharedSearch sharedSearch = pendingSearches.get(query);
            if (sharedSearch == null || sharedSearch.resultsFuture.isDone()) {
                sharedSearch = new SharedSearch(query, start.get());
                pendingSearches.put(query, sharedSearch);
            }
            return new PendingSearchHandle(sharedSearch);
        }
    }
}

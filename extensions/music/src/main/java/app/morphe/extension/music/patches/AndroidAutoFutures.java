/*
 * Copyright 2026 Morphe.
 * https://github.com/MorpheApp/morphe-patches
 *
 * See the included NOTICE file for GPLv3 Section 7 terms that apply to this code.
 */

package app.morphe.extension.music.patches;

import com.google.common.util.concurrent.ListenableFuture;

import java.util.concurrent.CompletableFuture;
import java.util.concurrent.Executor;
import java.util.function.Function;

final class AndroidAutoFutures {
    private AndroidAutoFutures() {}

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
}

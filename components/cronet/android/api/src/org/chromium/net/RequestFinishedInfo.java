// Copyright 2016 The Chromium Authors. All rights reserved.
// Use of this source code is governed by a BSD-style license that can be
// found in the LICENSE file.

package org.chromium.net;

import android.support.annotation.Nullable;

import java.util.Collection;
import java.util.concurrent.Executor;

/**
 * Information about a finished request. Passed to {@link RequestFinishedInfo.Listener}.
 *
 * {@hide} as it's a prototype.
 */
public final class RequestFinishedInfo {
    /**
     * Listens for finished requests for the purpose of collecting metrics.
     *
     * {@hide} as it's a prototype.
     */
    public abstract static class Listener {
        private final Executor mExecutor;

        public Listener(Executor executor) {
            if (executor == null) {
                throw new IllegalStateException("Executor must not be null");
            }
            mExecutor = executor;
        }

        /**
         * Invoked with request info. Will be called in a task submitted to the
         * {@link java.util.concurrent.Executor} returned by {@link #getExecutor}.
         * @param requestInfo {@link RequestFinishedInfo} for finished request.
         */
        public abstract void onRequestFinished(RequestFinishedInfo requestInfo);

        /**
         * Returns this listener's executor. Can be called on any thread.
         * @return this listener's {@link java.util.concurrent.Executor}
         */
        public Executor getExecutor() {
            return mExecutor;
        }
    }

    /**
     * Metrics collected for a single request.
     *
     * {@hide} as it's a prototype.
     */
    public static class Metrics {
        @Nullable
        private final Long mTtfbMs;
        @Nullable
        private final Long mTotalTimeMs;
        @Nullable
        private final Long mSentBytesCount;
        @Nullable
        private final Long mReceivedBytesCount;

        public Metrics(@Nullable Long ttfbMs, @Nullable Long totalTimeMs,
                @Nullable Long sentBytesCount, @Nullable Long receivedBytesCount) {
            mTtfbMs = ttfbMs;
            mTotalTimeMs = totalTimeMs;
            mSentBytesCount = sentBytesCount;
            mReceivedBytesCount = receivedBytesCount;
        }

        /**
         * Returns milliseconds between request initiation and first byte of response headers,
         * or null if not collected.
         */
        @Nullable
        public Long getTtfbMs() {
            return mTtfbMs;
        }

        /**
         * Returns milliseconds between request initiation and finish,
         * including a failure or cancellation, or null if not collected.
         */
        @Nullable
        public Long getTotalTimeMs() {
            return mTotalTimeMs;
        }

        /**
         * Returns total bytes sent over the network transport layer, or null if not collected.
         */
        @Nullable
        public Long getSentBytesCount() {
            return mSentBytesCount;
        }

        /**
         * Returns total bytes received over the network transport layer, or null if not collected.
         */
        @Nullable
        public Long getReceivedBytesCount() {
            return mReceivedBytesCount;
        }
    }

    private final String mUrl;
    private final Collection<Object> mAnnotations;
    private final Metrics mMetrics;
    @Nullable
    private final UrlResponseInfo mResponseInfo;

    /**
     * @hide only used by internal implementation.
     */
    public RequestFinishedInfo(String url, Collection<Object> annotations, Metrics metrics,
            @Nullable UrlResponseInfo responseInfo) {
        mUrl = url;
        mAnnotations = annotations;
        mMetrics = metrics;
        mResponseInfo = responseInfo;
    }

    /** Returns the request's original URL. */
    public String getUrl() {
        return mUrl;
    }

    /** Returns the objects that the caller has supplied when initiating the request. */
    public Collection<Object> getAnnotations() {
        return mAnnotations;
    }

    // TODO(klm): Collect and return a chain of Metrics objects for redirect responses.
    /**
     * Returns metrics collected for this request.
     *
     * <p>The reported times and bytes account for all redirects, i.e.
     * the TTFB is from the start of the original request to the ultimate response headers,
     * the TTLB is from the start of the original request to the end of the ultimate response,
     * the received byte count is for all redirects and the ultimate response combined.
     * These cumulative metric definitions are debatable, but are chosen to make sense
     * for user-facing latency analysis.
     *
     * @return metrics collected for this request.
     */
    public Metrics getMetrics() {
        return mMetrics;
    }

    /**
     * Returns a {@link UrlResponseInfo} for the request, if its response had started.
     * @return {@link UrlResponseInfo} for the request, if its response had started.
     */
    @Nullable
    public UrlResponseInfo getResponseInfo() {
        return mResponseInfo;
    }
}

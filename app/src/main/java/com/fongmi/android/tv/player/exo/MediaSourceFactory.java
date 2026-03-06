package com.fongmi.android.tv.player.exo;

import static androidx.media3.extractor.ts.DefaultTsPayloadReaderFactory.FLAG_ENABLE_HDMV_DTS_AUDIO_STREAMS;

import android.net.Uri;
import android.os.Bundle;

import androidx.annotation.NonNull;
import androidx.media3.common.C;
import androidx.media3.common.MediaItem;
import androidx.media3.datasource.DataSource;
import androidx.media3.datasource.DefaultDataSource;
import androidx.media3.datasource.cache.CacheDataSource;
import androidx.media3.datasource.okhttp.OkHttpDataSource;
import androidx.media3.exoplayer.drm.DrmSessionManagerProvider;
import androidx.media3.exoplayer.source.ConcatenatingMediaSource2;
import androidx.media3.exoplayer.source.DefaultMediaSourceFactory;
import androidx.media3.exoplayer.source.MediaSource;
import androidx.media3.exoplayer.upstream.LoadErrorHandlingPolicy;
import androidx.media3.extractor.DefaultExtractorsFactory;
import androidx.media3.extractor.ExtractorsFactory;
import androidx.media3.extractor.ts.TsExtractor;

import com.fongmi.android.tv.App;
import com.github.catvod.net.OkHttp;

import java.util.HashMap;
import java.util.Map;

public class MediaSourceFactory implements MediaSource.Factory {

    private final ExtractorsFactory extractorsFactory;

    public MediaSourceFactory() {
        extractorsFactory = new DefaultExtractorsFactory()
                .setTsExtractorFlags(FLAG_ENABLE_HDMV_DTS_AUDIO_STREAMS)
                .setTsExtractorTimestampSearchBytes(TsExtractor.DEFAULT_TIMESTAMP_SEARCH_BYTES * 10);
    }

    @NonNull
    @Override
    public MediaSource.Factory setDrmSessionManagerProvider(@NonNull DrmSessionManagerProvider drmSessionManagerProvider) {
        return this;
    }

    @NonNull
    @Override
    public MediaSource.Factory setLoadErrorHandlingPolicy(@NonNull LoadErrorHandlingPolicy loadErrorHandlingPolicy) {
        return this;
    }

    @NonNull
    @Override
    public @C.ContentType int[] getSupportedTypes() {
        return buildFactory(new HashMap<>()).getSupportedTypes();
    }

    @NonNull
    @Override
    public MediaSource createMediaSource(@NonNull MediaItem mediaItem) {
        DefaultMediaSourceFactory factory = buildFactory(extractHeaders(mediaItem));
        if (mediaItem.mediaId.contains("***") && mediaItem.mediaId.contains("|||")) {
            return createConcatenatingMediaSource(factory, mediaItem);
        } else {
            return factory.createMediaSource(mediaItem);
        }
    }

    private Map<String, String> extractHeaders(MediaItem mediaItem) {
        Map<String, String> headers = new HashMap<>();
        Bundle extras = mediaItem.requestMetadata.extras;
        if (extras != null) for (String key : extras.keySet()) headers.put(key, extras.getString(key));
        return headers;
    }

    private DefaultMediaSourceFactory buildFactory(Map<String, String> headers) {
        OkHttpDataSource.Factory http = new OkHttpDataSource.Factory(OkHttp.player()).setDefaultRequestProperties(headers);
        DataSource.Factory data = new CacheDataSource.Factory()
                .setCache(CacheManager.get().getCache())
                .setUpstreamDataSourceFactory(new DefaultDataSource.Factory(App.get(), http))
                .setCacheWriteDataSinkFactory(null)
                .setFlags(CacheDataSource.FLAG_IGNORE_CACHE_ON_ERROR);
        return new DefaultMediaSourceFactory(data, extractorsFactory);
    }

    private MediaSource createConcatenatingMediaSource(DefaultMediaSourceFactory factory, MediaItem mediaItem) {
        ConcatenatingMediaSource2.Builder builder = new ConcatenatingMediaSource2.Builder();
        for (String split : mediaItem.mediaId.split("\\*\\*\\*")) {
            String[] info = split.split("\\|\\|\\|");
            if (info.length >= 2) builder.add(factory.createMediaSource(mediaItem.buildUpon().setUri(Uri.parse(info[0])).build()), Long.parseLong(info[1]));
        }
        return builder.build();
    }
}

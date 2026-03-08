package com.fongmi.android.tv.player;

import java.util.Map;

/**
 * Read-only view of the current playback state; used by Server and SharingHelper.
 */
public interface PlaybackState {
    boolean isEmpty();

    String getUrl();

    Map<String, String> getHeaders();

    boolean isPlaying();

    boolean isVod();

    boolean isLive();

    long getPosition();

    long getDuration();

    float getSpeed();

    int getBackendPlaybackState();

    String getMetaTitle();

    String getMetaArtist();

    String getMetaArtUri();

    void seekTo(long positionMs);
}

package com.fongmi.android.tv.player;

import androidx.media3.common.PlaybackException;
import androidx.media3.common.Tracks;

interface CoreListener {
    void onIsPlayingChanged(boolean isPlaying);
    void onPlaybackStateChanged(int state);
    void onTracksChanged(Tracks tracks);
    void onVideoSizeChanged(int width, int height);
    void onPlayerError(PlaybackException e);
}

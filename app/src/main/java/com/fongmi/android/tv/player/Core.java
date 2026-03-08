package com.fongmi.android.tv.player;

import androidx.annotation.Nullable;
import androidx.media3.common.MediaMetadata;
import androidx.media3.ui.PlayerView;

import com.fongmi.android.tv.bean.Danmaku;
import com.fongmi.android.tv.bean.Drm;
import com.fongmi.android.tv.bean.Sub;
import com.fongmi.android.tv.bean.Track;

import java.util.List;
import java.util.Map;

import master.flame.danmaku.ui.widget.DanmakuView;

public interface Core {

    int EXOPLAYER = 0;
    int SOFT = 0;
    int HARD = 1;

    // Lifecycle
    void build();

    void release();

    void releaseOnly();

    // View management
    void attachView(PlayerView view);

    void detachView();

    // Danmaku
    void setDanmakuView(DanmakuView view);

    void setDanmaku(Danmaku item);

    void setDanmakuSize(float size);

    // Media loading
    void loadMedia(Map<String, String> headers, String url, String format, Drm drm, List<Sub> subs, @Nullable MediaMetadata metadata, int decode);

    void prepare();

    void clearMediaItems();

    // Playback control
    void play();

    void pause();

    void stop();

    void seekTo(long positionMs);

    void seekToDefaultPosition();

    // Decode
    int getDecode();

    void setDecode(int decode);

    boolean isHard();

    String getDecodeText();

    // State queries
    boolean isPlaying();

    boolean isEnded();

    boolean isIdle();

    int getPlaybackState();

    long getCurrentPosition();

    long getDuration();

    long getBufferedPosition();

    int getVideoWidth();

    int getVideoHeight();

    boolean haveTrack(int type);

    void resetTrack();

    void setTrack(List<Track> tracks);

    // Speed
    float getSpeed();

    String getSpeedText();

    String setSpeed(float speed);

    String addSpeed();

    String addSpeed(float value);

    String subSpeed(float value);

    String toggleSpeed();

    // Events
    void setCoreListener(CoreListener listener);

    // Identity
    int getType();

    boolean supportsNativeMediaSession();
}

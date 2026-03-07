package com.fongmi.android.tv.player.core;

import static androidx.media3.exoplayer.DefaultRenderersFactory.EXTENSION_RENDERER_MODE_ON;
import static androidx.media3.exoplayer.DefaultRenderersFactory.EXTENSION_RENDERER_MODE_PREFER;

import androidx.annotation.NonNull;
import androidx.annotation.Nullable;
import androidx.media3.common.AudioAttributes;
import androidx.media3.common.C;
import androidx.media3.common.MediaMetadata;
import androidx.media3.common.PlaybackException;
import androidx.media3.common.Player;
import androidx.media3.common.Tracks;
import androidx.media3.common.VideoSize;
import androidx.media3.exoplayer.ExoPlayer;
import androidx.media3.exoplayer.util.EventLogger;
import androidx.media3.ui.PlayerView;

import com.fongmi.android.tv.App;
import com.fongmi.android.tv.BuildConfig;
import com.fongmi.android.tv.R;
import com.fongmi.android.tv.Setting;
import com.fongmi.android.tv.bean.Danmaku;
import com.fongmi.android.tv.bean.Drm;
import com.fongmi.android.tv.bean.Sub;
import com.fongmi.android.tv.bean.Track;
import com.fongmi.android.tv.player.Core;
import com.fongmi.android.tv.player.CoreListener;
import com.fongmi.android.tv.player.SpeedController;
import com.fongmi.android.tv.player.danmaku.DanPlayer;
import com.fongmi.android.tv.player.exo.ExoUtil;
import com.fongmi.android.tv.player.exo.TrackUtil;
import com.fongmi.android.tv.utils.ResUtil;
import com.fongmi.android.tv.utils.UrlUtil;

import java.util.List;
import java.util.Map;

import master.flame.danmaku.ui.widget.DanmakuView;

public class ExoCore implements Core, Player.Listener {

    /** Notified when ExoPlayer is rebuilt (e.g. after toggleDecode), so PlaybackService can update MediaSession. */
    public interface RebuildListener {
        void onExoPlayerRebuilt(ExoPlayer newPlayer);
    }

    private final SpeedController speedCtrl;
    private CoreListener coreListener;
    private RebuildListener rebuildListener;
    private ExoPlayer exoPlayer;
    private DanPlayer danPlayer;
    private PlayerView view;
    private int decode;

    public ExoCore() {
        this.decode = HARD;
        this.speedCtrl = new SpeedController();
    }

    /** Returns the underlying ExoPlayer instance; used by PlaybackService to build MediaSession. */
    public ExoPlayer getExoPlayer() {
        return exoPlayer;
    }

    public void setRebuildListener(RebuildListener listener) {
        this.rebuildListener = listener;
    }

    // ---- Core lifecycle ----

    @Override
    public void build() {
        exoPlayer = new ExoPlayer.Builder(App.get())
                .setLoadControl(ExoUtil.buildLoadControl())
                .setTrackSelector(ExoUtil.buildTrackSelector())
                .setRenderersFactory(ExoUtil.buildRenderersFactory(decode == HARD ? EXTENSION_RENDERER_MODE_ON : EXTENSION_RENDERER_MODE_PREFER))
                .setMediaSourceFactory(ExoUtil.buildMediaSourceFactory())
                .build();
        if (BuildConfig.DEBUG) exoPlayer.addAnalyticsListener(new EventLogger());
        exoPlayer.setAudioAttributes(AudioAttributes.DEFAULT, true);
        exoPlayer.setHandleAudioBecomingNoisy(true);
        exoPlayer.setWakeMode(C.WAKE_MODE_NETWORK);
        exoPlayer.setPlayWhenReady(true);
        exoPlayer.addListener(this);
        speedCtrl.setPlayer(exoPlayer);
        if (danPlayer != null) danPlayer.setPlayer(exoPlayer);
    }

    @Override
    public void release() {
        if (exoPlayer != null) exoPlayer.release();
        if (danPlayer != null) danPlayer.release();
        if (view != null) view.setPlayer(null);
        exoPlayer = null;
    }

    /** Releases only ExoPlayer (not DanPlayer or view); used before rebuild for toggleDecode. */
    @Override
    public void releaseOnly() {
        if (danPlayer != null) danPlayer.setPlayer(null);
        if (exoPlayer != null) exoPlayer.release();
        exoPlayer = null;
    }

    // ---- View ----

    @Override
    public void attachView(PlayerView view) {
        if (exoPlayer == null) return;
        view.setRender(Setting.getRender());
        view.setPlayer(exoPlayer);
        this.view = view;
    }

    @Override
    public void detachView() {
        if (view != null) view.setPlayer(null);
        view = null;
    }

    // ---- Danmaku ----

    @Override
    public void setDanmakuView(DanmakuView dmView) {
        danPlayer = new DanPlayer();
        danPlayer.setPlayer(exoPlayer);
        danPlayer.setView(dmView);
    }

    @Override
    public void setDanmaku(Danmaku item) {
        if (danPlayer != null) danPlayer.setDanmaku(item);
    }

    @Override
    public void setDanmakuSize(float size) {
        if (danPlayer != null) danPlayer.setTextSize(size);
    }

    // ---- Media loading ----

    @Override
    public void loadMedia(Map<String, String> headers, String url, String format, Drm drm, List<Sub> subs, @Nullable MediaMetadata metadata, int decode) {
        if (exoPlayer != null) exoPlayer.setMediaItem(ExoUtil.getMediaItem(headers, UrlUtil.uri(url), format, drm, subs, decode, metadata));
    }

    @Override
    public void prepare() {
        if (exoPlayer != null) exoPlayer.prepare();
    }

    @Override
    public void clearMediaItems() {
        if (exoPlayer != null) exoPlayer.clearMediaItems();
    }

    // ---- Control ----

    @Override
    public void play() {
        if (exoPlayer != null) exoPlayer.play();
    }

    @Override
    public void pause() {
        if (exoPlayer != null) exoPlayer.pause();
    }

    @Override
    public void stop() {
        if (exoPlayer != null) exoPlayer.stop();
    }

    @Override
    public void seekTo(long positionMs) {
        if (exoPlayer != null) exoPlayer.seekTo(positionMs);
    }

    @Override
    public void seekToDefaultPosition() {
        if (exoPlayer != null) exoPlayer.seekToDefaultPosition();
        prepare();
    }

    // ---- Decode ----

    @Override
    public int getDecode() {
        return decode;
    }

    @Override
    public void setDecode(int decode) {
        if (this.decode == decode) return;
        this.decode = decode;
        releaseOnly();
        build();
        if (view != null) attachView(view);
        if (rebuildListener != null) rebuildListener.onExoPlayerRebuilt(exoPlayer);
    }

    @Override
    public boolean isHard() {
        return decode == HARD;
    }

    @Override
    public String getDecodeText() {
        return ResUtil.getStringArray(R.array.select_decode)[decode];
    }

    // ---- State queries ----

    @Override
    public boolean isPlaying() {
        return exoPlayer != null && exoPlayer.isPlaying();
    }

    @Override
    public boolean isEnded() {
        return exoPlayer != null && exoPlayer.getPlaybackState() == Player.STATE_ENDED;
    }

    @Override
    public boolean isIdle() {
        return exoPlayer != null && exoPlayer.getPlaybackState() == Player.STATE_IDLE;
    }

    @Override
    public int getPlaybackState() {
        return exoPlayer == null ? Player.STATE_IDLE : exoPlayer.getPlaybackState();
    }

    @Override
    public long getCurrentPosition() {
        return exoPlayer == null ? C.TIME_UNSET : exoPlayer.getCurrentPosition();
    }

    @Override
    public long getDuration() {
        return exoPlayer == null ? -1 : exoPlayer.getDuration();
    }

    @Override
    public long getBufferedPosition() {
        return exoPlayer == null ? 0 : exoPlayer.getBufferedPosition();
    }

    @Override
    public int getVideoWidth() {
        return exoPlayer == null ? 0 : exoPlayer.getVideoSize().width;
    }

    @Override
    public int getVideoHeight() {
        return exoPlayer == null ? 0 : exoPlayer.getVideoSize().height;
    }

    @Override
    public boolean haveTrack(int type) {
        return exoPlayer != null && TrackUtil.count(exoPlayer.getCurrentTracks(), type) > 0;
    }

    @Override
    public void resetTrack() {
        if (exoPlayer != null) TrackUtil.reset(exoPlayer);
    }

    @Override
    public void setTrack(List<Track> tracks) {
        if (exoPlayer != null && !tracks.isEmpty()) TrackUtil.setTrackSelection(exoPlayer, tracks);
    }

    // ---- Speed ----

    @Override
    public float getSpeed() {
        return speedCtrl.getSpeed();
    }

    @Override
    public String getSpeedText() {
        return speedCtrl.getSpeedText();
    }

    @Override
    public String setSpeed(float speed) {
        return speedCtrl.setSpeed(speed);
    }

    @Override
    public String addSpeed() {
        return speedCtrl.addSpeed();
    }

    @Override
    public String addSpeed(float value) {
        return speedCtrl.addSpeed(value);
    }

    @Override
    public String subSpeed(float value) {
        return speedCtrl.subSpeed(value);
    }

    @Override
    public String toggleSpeed() {
        return speedCtrl.toggleSpeed();
    }

    // ---- Listener / identity ----

    @Override
    public void setCoreListener(CoreListener listener) {
        this.coreListener = listener;
    }

    @Override
    public int getType() {
        return EXOPLAYER;
    }

    @Override
    public boolean supportsNativeMediaSession() {
        return true;
    }

    // ---- Player.Listener bridge → CoreListener ----

    @Override
    public void onIsPlayingChanged(boolean isPlaying) {
        if (coreListener != null) coreListener.onIsPlayingChanged(isPlaying);
    }

    @Override
    public void onPlaybackStateChanged(int state) {
        if (coreListener != null) coreListener.onPlaybackStateChanged(state);
    }

    @Override
    public void onTracksChanged(@NonNull Tracks tracks) {
        if (coreListener != null) coreListener.onTracksChanged(tracks);
    }

    @Override
    public void onVideoSizeChanged(@NonNull VideoSize videoSize) {
        if (coreListener != null) coreListener.onVideoSizeChanged(videoSize.width, videoSize.height);
    }

    @Override
    public void onPlayerError(@NonNull PlaybackException e) {
        if (coreListener != null) coreListener.onPlayerError(e);
    }
}

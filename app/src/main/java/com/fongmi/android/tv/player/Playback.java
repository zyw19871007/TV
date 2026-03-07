package com.fongmi.android.tv.player;

import android.content.Intent;
import android.net.Uri;

import androidx.annotation.Nullable;
import androidx.media3.common.C;
import androidx.media3.common.PlaybackException;
import androidx.media3.common.Tracks;
import androidx.media3.exoplayer.ExoPlayer;
import androidx.media3.exoplayer.drm.FrameworkMediaDrm;
import androidx.media3.ui.PlayerView;

import com.fongmi.android.tv.App;
import com.fongmi.android.tv.Constant;
import com.fongmi.android.tv.R;
import com.fongmi.android.tv.bean.Danmaku;
import com.fongmi.android.tv.bean.Result;
import com.fongmi.android.tv.bean.Sub;
import com.fongmi.android.tv.bean.Track;
import com.fongmi.android.tv.player.core.ExoCore;
import com.fongmi.android.tv.player.exo.ExoUtil;
import com.fongmi.android.tv.server.Server;
import com.fongmi.android.tv.utils.ResUtil;
import com.fongmi.android.tv.utils.UrlUtil;
import com.fongmi.android.tv.utils.Util;
import com.github.catvod.utils.Path;

import java.util.Formatter;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.concurrent.TimeUnit;

import master.flame.danmaku.ui.widget.DanmakuView;

/**
 * Central facade for playback control. Replaces the former {@code Players} God Class.
 *
 * <p>Responsibilities:
 * <ul>
 *   <li>Orchestrates {@link Core} (backend) and {@link PlaybackSession} (loading pipeline).
 *   <li>Handles error-retry logic.
 *   <li>Implements {@link PlaybackState} for read-only access by Server and SharingHelper.
 *   <li>Implements {@link CoreListener} to receive events from the Core.
 *   <li>Implements {@link PlaybackSession.Listener} to receive media-ready / error signals.
 * </ul>
 */
public class Playback implements PlaybackState, CoreListener, PlaybackSession.Listener {

    private final Core core;
    private final PlaybackSession session;
    private final SharingHelper sharingHelper;
    private final StringBuilder builder;
    private final Formatter formatter;

    private PlayerListener listener;
    private boolean trackInitialized;
    private int retry;

    // ---- Factory ----

    public static Playback create() {
        Playback p = new Playback(new ExoCore());
        Server.get().setPlayer(p);
        return p;
    }

    private Playback(ExoCore core) {
        this.core = core;
        this.session = new PlaybackSession(core, this);
        this.sharingHelper = new SharingHelper(this);
        this.builder = new StringBuilder();
        this.formatter = new Formatter(builder, Locale.getDefault());
    }

    // ---- PlaybackService integration ----

    /** Builds the ExoPlayer without attaching a view; called by PlaybackService.onCreate(). */
    public void buildExoPlayer() {
        core.release();
        core.build();
    }

    /** Returns the underlying ExoPlayer for MediaSession construction; null if not ExoCore. */
    @Nullable
    public ExoPlayer getExoPlayer() {
        if (core instanceof ExoCore) return ((ExoCore) core).getExoPlayer();
        return null;
    }

    /** Alias for {@link #getExoPlayer()}; used by dialogs that need direct ExoPlayer access. */
    @Nullable
    public ExoPlayer get() {
        return getExoPlayer();
    }

    /** Registers a listener notified when ExoPlayer is rebuilt after toggleDecode(). */
    public void setOnExoPlayerRebuildListener(ExoCore.RebuildListener listener) {
        if (core instanceof ExoCore) ((ExoCore) core).setRebuildListener(listener);
    }

    // ---- View lifecycle ----

    /** Attaches a PlayerView after Activity binds to the service. */
    public void attachView(PlayerView view) {
        core.setCoreListener(this);
        core.attachView(view);
    }

    /** Detaches the PlayerView when Activity is destroyed; ExoPlayer keeps running. */
    public void detachView() {
        core.detachView();
        listener = null;
    }

    // ---- Danmaku ----

    public void setDanmakuView(DanmakuView view) {
        core.setDanmakuView(view);
    }

    public void setDanmaku(Danmaku item) {
        core.setDanmaku(item);
        session.applyDanmaku(item);
    }

    public void setDanmakuSize(float size) {
        core.setDanmakuSize(size);
    }

    public List<Danmaku> getDanmakus() {
        return session.getParams().getDanmakus();
    }

    public boolean haveDanmaku() {
        return session.getParams().haveDanmaku();
    }

    // ---- Listener ----

    public void setListener(PlayerListener listener) {
        this.listener = listener;
    }

    // ---- Session control ----

    public void reset() {
        session.cancelTimeout();
        retry = 0;
    }

    public void clear() {
        session.clear();
    }

    // ---- PlaybackState (read-only, used by Server / SharingHelper) ----

    @Override public boolean isEmpty()                 { return session.getParams().isEmpty(); }
    @Override public String getUrl()                   { return session.getParams().getUrl(); }
    @Override public Map<String, String> getHeaders()  { return session.getParams().getHeaders(); }
    @Override public boolean isPlaying()               { return core.isPlaying(); }
    @Override public long getPosition()                { return core.getCurrentPosition(); }
    @Override public long getDuration()                { return core.getDuration(); }
    @Override public float getSpeed()                  { return core.getSpeed(); }
    @Override public String getMetaTitle()             { return session.getParams().getMetaTitle(); }
    @Override public String getMetaArtist()            { return session.getParams().getMetaArtist(); }
    @Override public String getMetaArtUri()            { return session.getParams().getMetaArtUri(); }
    @Override public void seekTo(long positionMs)      { core.seekTo(positionMs); }

    @Override
    public boolean isVod() {
        return getDuration() > TimeUnit.MINUTES.toMillis(1) && !isCurrentItemLive();
    }

    @Override
    public boolean isLive() {
        return getDuration() < TimeUnit.MINUTES.toMillis(1) || isCurrentItemLive();
    }

    @Override
    public int getBackendPlaybackState() {
        return core.getPlaybackState();
    }

    private boolean isCurrentItemLive() {
        ExoPlayer ep = getExoPlayer();
        return ep != null && ep.isCurrentMediaItemLive();
    }

    // ---- Additional helpers (used by Activities / dialogs) ----

    public long getBuffered()             { return core.getBufferedPosition(); }
    public boolean isEnded()             { return core.isEnded(); }
    public boolean isIdle()              { return core.isIdle(); }
    public boolean isHard()              { return core.isHard(); }
    public boolean isPortrait()          { return core.getVideoHeight() > core.getVideoWidth(); }
    public boolean isLandscape()         { return core.getVideoWidth() > core.getVideoHeight(); }
    public int getVideoWidth()           { return core.getVideoWidth(); }
    public int getVideoHeight()          { return core.getVideoHeight(); }
    public boolean haveTrack(int type)   { return core.haveTrack(type); }
    public String getDecodeText()        { return core.getDecodeText(); }
    public String getSpeedText()         { return core.getSpeedText(); }
    public String setSpeed(float speed)  { return core.setSpeed(speed); }
    public String addSpeed()             { return core.addSpeed(); }
    public String addSpeed(float value)  { return core.addSpeed(value); }
    public String subSpeed(float value)  { return core.subSpeed(value); }
    public String toggleSpeed()          { return core.toggleSpeed(); }
    public void clearMediaItems()        { core.clearMediaItems(); }
    public void resetTrack()             { core.resetTrack(); }
    public void setTrack(List<Track> tracks) { core.setTrack(tracks); }

    public String getKey()               { return session.getParams().getKey(); }
    public void setKey(String key)       { session.getParams().setKey(key); }

    public void setMetadata(String title, String artist, String artUri) {
        session.getParams().setMetadata(title, artist, artUri);
    }

    public String getSizeText() {
        int w = core.getVideoWidth(), h = core.getVideoHeight();
        return w == 0 && h == 0 ? "" : w + " x " + h;
    }

    public boolean canSetOpening(long position, long duration) {
        return position > 0 && duration > 0 && position <= Constant.getOpEdLimit(duration);
    }

    public boolean canSetEnding(long position, long duration) {
        return position > 0 && duration > 0 && duration - position <= Constant.getOpEdLimit(duration);
    }

    public String stringToTime(long time) {
        return Util.format(builder, formatter, time);
    }

    public String getPositionTime(long offset) {
        long time = getPosition() + offset;
        if (time > getDuration()) time = getDuration();
        else if (time < 0) time = 0;
        return stringToTime(time);
    }

    public String getDurationTime() {
        long time = getDuration();
        if (time < 0) time = 0;
        return stringToTime(time);
    }

    // ---- Seek helpers ----

    public void seek(long offset) {
        core.seekTo(getPosition() + offset);
    }

    public void seekToDefaultPosition() {
        core.seekToDefaultPosition();
    }

    // ---- Playback controls ----

    public void play()    { core.play(); }
    public void pause()   { core.pause(); }

    public void stop() {
        core.stop();
        session.stopParse();
    }

    public void prepare() { core.prepare(); }

    public void release() {
        session.stopParse();
        session.cancelTimeout();
        core.release();
        Server.get().setPlayer(null);
        App.execute(() -> Source.get().stop());
    }

    // ---- Media loading ----

    public void start(Result result, boolean useParse, long timeout) {
        if (result.getDrm() != null && !FrameworkMediaDrm.isCryptoSchemeSupported(result.getDrm().getUUID())) {
            if (listener != null) listener.onError(ResUtil.getString(R.string.error_play_drm));
        } else if (result.hasMsg()) {
            if (listener != null) listener.onError(result.getMsg());
        } else if (isIllegal(result.getRealUrl())) {
            if (listener != null) listener.onError(ResUtil.getString(R.string.error_play_url));
        } else {
            reset();
            session.start(result, useParse, timeout);
        }
    }

    /** Re-loads the current media item (e.g. after changing format/sub). */
    public void setMediaItem() {
        session.reload();
    }

    /** Loads a new URL using existing params (headers, format, drm, subs). */
    public void setMediaItem(String url) {
        session.loadUrl(url);
    }

    public void setSub(Sub sub) {
        session.setSub(sub);
    }

    public void setFormat(String format) {
        session.setFormat(format);
    }

    // ---- Decode toggle ----

    public void toggleDecode() {
        long position = core.getCurrentPosition();
        core.setDecode(core.isHard() ? Core.SOFT : Core.HARD);
        session.reload();
        if (position > C.TIME_UNSET && position > 0) core.seekTo(position);
    }

    // ---- Sharing ----

    public void share(android.app.Activity activity, CharSequence title) {
        sharingHelper.share(activity, title);
    }

    public void choose(android.app.Activity activity, CharSequence title) {
        sharingHelper.choose(activity, title);
    }

    public void checkData(Intent data) {
        sharingHelper.checkData(data);
    }

    // ---- PlaybackSession.Listener ----

    @Override
    public void onMediaReady(Map<String, String> headers, String url, List<Danmaku> danmakus, long timeout) {
        if (danmakus != null && !danmakus.isEmpty()) {
            core.setDanmaku(danmakus.get(0));
        } else {
            core.setDanmaku(Danmaku.empty());
        }
        if (listener != null) listener.onPrepare();
        trackInitialized = false;
    }

    @Override
    public void onParseError() {
        if (listener != null) listener.onError(ResUtil.getString(R.string.error_play_parse));
    }

    @Override
    public void onTimeout() {
        if (listener != null) listener.onError(ResUtil.getString(R.string.error_play_timeout));
    }

    // ---- CoreListener ----

    @Override
    public void onIsPlayingChanged(boolean isPlaying) {
        if (listener != null) listener.onPlaying();
    }

    @Override
    public void onPlaybackStateChanged(int state) {
        if (listener != null) listener.onState(state);
    }

    @Override
    public void onVideoSizeChanged(int width, int height) {
        if (listener != null) listener.onSize();
    }

    @Override
    public void onTracksChanged(Tracks tracks) {
        if (tracks.isEmpty() || trackInitialized) return;
        core.setTrack(Track.find(getKey()));
        if (listener != null) listener.onTrack();
        trackInitialized = true;
    }

    @Override
    public void onPlayerError(PlaybackException e) {
        if (++retry > 2) {
            if (listener != null) listener.onError(e.getErrorCodeName());
            return;
        }
        switch (e.errorCode) {
            case PlaybackException.ERROR_CODE_BEHIND_LIVE_WINDOW:
                core.seekToDefaultPosition();
                break;
            case PlaybackException.ERROR_CODE_DECODER_INIT_FAILED:
            case PlaybackException.ERROR_CODE_DECODER_QUERY_FAILED:
            case PlaybackException.ERROR_CODE_DECODING_FAILED:
                toggleDecode();
                break;
            case PlaybackException.ERROR_CODE_IO_UNSPECIFIED:
            case PlaybackException.ERROR_CODE_PARSING_CONTAINER_MALFORMED:
            case PlaybackException.ERROR_CODE_PARSING_MANIFEST_MALFORMED:
            case PlaybackException.ERROR_CODE_PARSING_CONTAINER_UNSUPPORTED:
            case PlaybackException.ERROR_CODE_PARSING_MANIFEST_UNSUPPORTED:
                session.setFormat(ExoUtil.getMimeType(e.errorCode));
                break;
            default:
                if (listener != null) listener.onError(e.getErrorCodeName());
                break;
        }
    }

    // ---- Private helpers ----

    private boolean isIllegal(String url) {
        Uri uri = UrlUtil.uri(url);
        String host = UrlUtil.host(uri);
        String scheme = UrlUtil.scheme(uri);
        if ("data".equals(scheme)) return false;
        return scheme.isEmpty() || "file".equals(scheme) ? !Path.exists(url) : host.isEmpty();
    }
}

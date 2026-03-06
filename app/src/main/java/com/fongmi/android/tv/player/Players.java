package com.fongmi.android.tv.player;

import static androidx.media3.exoplayer.DefaultRenderersFactory.EXTENSION_RENDERER_MODE_ON;
import static androidx.media3.exoplayer.DefaultRenderersFactory.EXTENSION_RENDERER_MODE_PREFER;

import android.content.Intent;
import android.net.Uri;
import android.text.TextUtils;

import androidx.annotation.NonNull;
import androidx.media3.common.AudioAttributes;
import androidx.media3.common.C;
import androidx.media3.common.PlaybackException;
import androidx.media3.common.Player;
import androidx.media3.common.Tracks;
import androidx.media3.common.VideoSize;
import androidx.media3.exoplayer.ExoPlayer;
import androidx.media3.exoplayer.drm.FrameworkMediaDrm;
import androidx.media3.exoplayer.util.EventLogger;
import androidx.media3.ui.PlayerView;

import com.fongmi.android.tv.App;
import com.fongmi.android.tv.BuildConfig;
import com.fongmi.android.tv.Constant;
import com.fongmi.android.tv.R;
import com.fongmi.android.tv.Setting;
import com.fongmi.android.tv.bean.Danmaku;
import com.fongmi.android.tv.bean.Drm;
import com.fongmi.android.tv.bean.Result;
import com.fongmi.android.tv.bean.Sub;
import com.fongmi.android.tv.bean.Track;
import com.fongmi.android.tv.event.ActionEvent;
import com.fongmi.android.tv.event.ErrorEvent;
import com.fongmi.android.tv.event.PlayerEvent;
import com.fongmi.android.tv.impl.ParseCallback;
import com.fongmi.android.tv.player.danmaku.DanPlayer;
import com.fongmi.android.tv.player.exo.ExoUtil;
import com.fongmi.android.tv.player.exo.TrackUtil;
import com.fongmi.android.tv.server.Server;
import com.fongmi.android.tv.utils.Notify;
import com.fongmi.android.tv.utils.ResUtil;
import com.fongmi.android.tv.utils.UrlUtil;
import com.github.catvod.utils.Path;
import com.google.common.net.HttpHeaders;
import com.orhanobut.logger.Logger;

import java.util.ArrayList;
import java.util.Formatter;
import java.util.HashMap;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.concurrent.TimeUnit;

import master.flame.danmaku.ui.widget.DanmakuView;

public class Players implements Player.Listener, ParseCallback {

    private static final String TAG = Players.class.getSimpleName();

    public static final int SOFT = 0;
    public static final int HARD = 1;

    private final PlaybackParams params;
    private final SpeedController speedCtrl;
    private final SharingHelper sharingHelper;
    private final StringBuilder builder;
    private final Formatter formatter;
    private final Runnable runnable;

    private ExoPlayer exoPlayer;
    private DanPlayer danPlayer;
    private ParseJob parseJob;
    private PlayerView view;
    private String tag;

    private boolean initTrack;
    private int decode;
    private int retry;

    public static Players create() {
        Players player = new Players();
        Server.get().setPlayer(player);
        return player;
    }

    private Players() {
        decode = HARD;
        params = new PlaybackParams();
        builder = new StringBuilder();
        speedCtrl = new SpeedController();
        sharingHelper = new SharingHelper(this);
        runnable = () -> ErrorEvent.timeout(tag);
        formatter = new Formatter(builder, Locale.getDefault());
    }

    public void init(PlayerView view) {
        releasePlayer();
        setPlayer(view);
        setMediaItem();
    }

    private void setPlayer(PlayerView view) {
        exoPlayer = new ExoPlayer.Builder(App.get())
                .setLoadControl(ExoUtil.buildLoadControl())
                .setTrackSelector(ExoUtil.buildTrackSelector())
                .setRenderersFactory(ExoUtil.buildRenderersFactory(isHard() ? EXTENSION_RENDERER_MODE_ON : EXTENSION_RENDERER_MODE_PREFER))
                .setMediaSourceFactory(ExoUtil.buildMediaSourceFactory())
                .build();
        if (BuildConfig.DEBUG) exoPlayer.addAnalyticsListener(new EventLogger());
        exoPlayer.setAudioAttributes(AudioAttributes.DEFAULT, true);
        exoPlayer.setHandleAudioBecomingNoisy(true);
        exoPlayer.setWakeMode(C.WAKE_MODE_NETWORK);
        view.setRender(Setting.getRender());
        exoPlayer.setPlayWhenReady(true);
        exoPlayer.addListener(this);
        speedCtrl.setPlayer(exoPlayer);
        if (danPlayer != null) danPlayer.setPlayer(exoPlayer);
        view.setPlayer(exoPlayer);
        this.view = view;
    }

    public void setDanmakuView(DanmakuView view) {
        danPlayer = new DanPlayer();
        danPlayer.setPlayer(exoPlayer);
        danPlayer.setView(view);
    }

    public ExoPlayer getExoPlayer() {
        return exoPlayer;
    }

    public List<Danmaku> getDanmakus() {
        return params.danmakus;
    }

    public String getUrl() {
        return params.url;
    }

    public Map<String, String> getHeaders() {
        return params.getHeaders();
    }

    public void setSub(Sub sub) {
        params.sub = sub;
        setMediaItem();
    }

    public void setFormat(String format) {
        params.format = format;
        setMediaItem();
    }

    public String getKey() {
        return params.getKey();
    }

    public void setKey(String key) {
        params.setKey(key);
    }

    public String getTag() {
        return tag;
    }

    public void setTag(String tag) {
        this.tag = tag;
    }

    public void reset() {
        removeTimeoutCheck();
        retry = 0;
    }

    public void clearMediaItems() {
        if (exoPlayer != null) exoPlayer.clearMediaItems();
    }

    public void clear() {
        params.clear();
    }

    public String stringToTime(long time) {
        return Util.format(builder, formatter, time);
    }

    public int getVideoWidth() {
        return exoPlayer == null ? 0 : exoPlayer.getVideoSize().width;
    }

    public int getVideoHeight() {
        return exoPlayer == null ? 0 : exoPlayer.getVideoSize().height;
    }

    public float getSpeed() {
        return speedCtrl.getSpeed();
    }

    public long getPosition() {
        return exoPlayer == null ? C.TIME_UNSET : exoPlayer.getCurrentPosition();
    }

    public long getDuration() {
        return exoPlayer == null ? -1 : exoPlayer.getDuration();
    }

    public long getBuffered() {
        return exoPlayer == null ? 0 : exoPlayer.getBufferedPosition();
    }

    public boolean haveTrack(int type) {
        return exoPlayer != null && TrackUtil.count(exoPlayer.getCurrentTracks(), type) > 0;
    }

    public boolean haveDanmaku() {
        return params.haveDanmaku();
    }

    public boolean canSetOpening(long position, long duration) {
        return position > 0 && duration > 0 && position <= Constant.getOpEdLimit(duration);
    }

    public boolean canSetEnding(long position, long duration) {
        return position > 0 && duration > 0 && duration - position <= Constant.getOpEdLimit(duration);
    }

    public boolean isPlaying() {
        return exoPlayer != null && exoPlayer.isPlaying();
    }

    public boolean isEnded() {
        return exoPlayer != null && exoPlayer.getPlaybackState() == Player.STATE_ENDED;
    }

    public boolean isIdle() {
        return exoPlayer != null && exoPlayer.getPlaybackState() == Player.STATE_IDLE;
    }

    public boolean isEmpty() {
        return params.isEmpty();
    }

    public boolean isLive() {
        return getDuration() < TimeUnit.MINUTES.toMillis(1) || exoPlayer.isCurrentMediaItemLive();
    }

    public boolean isVod() {
        return getDuration() > TimeUnit.MINUTES.toMillis(1) && !exoPlayer.isCurrentMediaItemLive();
    }

    public boolean isHard() {
        return decode == HARD;
    }

    public boolean isPortrait() {
        return getVideoHeight() > getVideoWidth();
    }

    public boolean isLandscape() {
        return getVideoWidth() > getVideoHeight();
    }

    public String getSizeText() {
        return getVideoWidth() == 0 && getVideoHeight() == 0 ? "" : getVideoWidth() + " x " + getVideoHeight();
    }

    public String getSpeedText() {
        return speedCtrl.getSpeedText();
    }

    public String getDecodeText() {
        return ResUtil.getStringArray(R.array.select_decode)[decode];
    }

    public String setSpeed(float speed) {
        return speedCtrl.setSpeed(speed);
    }

    public String addSpeed() {
        return speedCtrl.addSpeed();
    }

    public String addSpeed(float value) {
        return speedCtrl.addSpeed(value);
    }

    public String subSpeed(float value) {
        return speedCtrl.subSpeed(value);
    }

    public String toggleSpeed() {
        return speedCtrl.toggleSpeed();
    }

    public void toggleDecode() {
        decode = isHard() ? SOFT : HARD;
        init(view);
    }

    public String getPositionTime(long time) {
        time = getPosition() + time;
        if (time > getDuration()) time = getDuration();
        else if (time < 0) time = 0;
        return stringToTime(time);
    }

    public String getDurationTime() {
        long time = getDuration();
        if (time < 0) time = 0;
        return stringToTime(time);
    }

    public void seek(long time) {
        seekTo(getPosition() + time);
    }

    public void seekTo(long time) {
        if (exoPlayer != null) exoPlayer.seekTo(time);
    }

    public void seekToDefaultPosition() {
        if (exoPlayer != null) exoPlayer.seekToDefaultPosition();
        prepare();
    }

    public void prepare() {
        if (exoPlayer != null) exoPlayer.prepare();
    }

    public void play() {
        if (exoPlayer != null) exoPlayer.play();
    }

    public void pause() {
        if (exoPlayer != null) exoPlayer.pause();
    }

    public void stop() {
        if (exoPlayer != null) exoPlayer.stop();
        if (danPlayer != null) danPlayer.stop();
        stopParse();
    }

    public void release() {
        stopParse();
        releasePlayer();
        removeTimeoutCheck();
        Server.get().setPlayer(null);
        App.execute(() -> Source.get().stop());
    }

    private void releasePlayer() {
        if (exoPlayer != null) exoPlayer.release();
        if (danPlayer != null) danPlayer.release();
        if (view != null) view.setPlayer(null);
        exoPlayer = null;
    }

    private void removeTimeoutCheck() {
        App.removeCallbacks(runnable);
    }

    public void start(Result result, boolean useParse, long timeout) {
        if (result.getDrm() != null && !FrameworkMediaDrm.isCryptoSchemeSupported(result.getDrm().getUUID())) {
            ErrorEvent.drm(tag);
        } else if (result.hasMsg()) {
            ErrorEvent.extract(tag, result.getMsg());
        } else if (result.getParse() == 1 || result.getJx() == 1) {
            startParse(result, useParse);
        } else if (isIllegal(result.getRealUrl())) {
            ErrorEvent.url(tag);
        } else {
            setMediaItem(result, timeout);
        }
    }

    private void startParse(Result result, boolean useParse) {
        stopParse();
        params.drm = result.getDrm();
        params.subs = result.getSubs();
        params.format = result.getFormat();
        params.danmakus = result.getDanmaku();
        parseJob = ParseJob.create(this).start(result, useParse);
    }

    private void stopParse() {
        if (parseJob != null) parseJob.stop();
        parseJob = null;
    }

    public void setMediaItem() {
        if (params.url != null) setMediaItem(params.headers, params.url, params.format, params.drm, params.subs, params.danmakus, Constant.TIMEOUT_PLAY);
    }

    public void setMediaItem(String url) {
        setMediaItem(new HashMap<>(), url);
    }

    private void setMediaItem(Map<String, String> headers, String url) {
        setMediaItem(headers, url, params.format, params.drm, params.subs, params.danmakus, Constant.TIMEOUT_PLAY);
    }

    private void setMediaItem(Result result, long timeout) {
        setMediaItem(result.getHeader(), result.getRealUrl(), result.getFormat(), result.getDrm(), result.getSubs(), result.getDanmaku(), timeout);
    }

    private void setMediaItem(Map<String, String> headers, String url, String format, Drm drm, List<Sub> subs, List<Danmaku> danmakus, long timeout) {
        params.headers = params.checkUa(headers);
        params.url = url;
        params.format = format;
        params.drm = drm;
        params.subs = subs;
        params.danmakus = danmakus;
        if (exoPlayer != null) exoPlayer.setMediaItem(ExoUtil.getMediaItem(params.headers, UrlUtil.uri(url), format, drm, params.checkSub(subs), decode, params.buildMediaMetadata()));
        Logger.t(TAG).d("headers=%s\nurl=%s\nformat=%s\ndrm=%s\nsubs=%s\ndanmakus=%s\ntimeout=%s", params.headers, url, format, drm, params.subs, danmakus, timeout);
        if (danPlayer != null) setDanmaku(danmakus == null || danmakus.isEmpty() ? Danmaku.empty() : danmakus.get(0));
        App.post(runnable, timeout);
        PlayerEvent.prepare(tag);
        initTrack = false;
        prepare();
    }

    public void setDanmaku(Danmaku item) {
        danPlayer.setDanmaku(item);
        if (params.danmakus == null) params.danmakus = new ArrayList<>();
        if (!item.isEmpty() && !params.danmakus.contains(item)) params.danmakus.add(0, item);
        params.danmakus.forEach(d -> d.setSelected(d.getUrl().equals(item.getUrl())));
    }

    public void setDanmakuSize(float size) {
        if (danPlayer != null) danPlayer.setTextSize(size);
    }

    public void resetTrack() {
        if (exoPlayer != null) TrackUtil.reset(exoPlayer);
    }

    public void setTrack(List<Track> tracks) {
        if (exoPlayer != null && !tracks.isEmpty()) TrackUtil.setTrackSelection(exoPlayer, tracks);
    }

    private boolean isIllegal(String url) {
        Uri uri = UrlUtil.uri(url);
        String host = UrlUtil.host(uri);
        String scheme = UrlUtil.scheme(uri);
        if ("data".equals(scheme)) return false;
        return scheme.isEmpty() || "file".equals(scheme) ? !Path.exists(url) : host.isEmpty();
    }

    public String getMetaTitle() {
        return params.getMetaTitle();
    }

    public String getMetaArtist() {
        return params.getMetaArtist();
    }

    public String getMetaArtUri() {
        return params.getMetaArtUri();
    }

    public void setMetadata(String title, String artist, String artUri) {
        params.setMetadata(title, artist, artUri);
        ActionEvent.update();
    }

    public void share(android.app.Activity activity, CharSequence title) {
        sharingHelper.share(activity, title);
    }

    public void choose(android.app.Activity activity, CharSequence title) {
        sharingHelper.choose(activity, title);
    }

    public void checkData(Intent data) {
        sharingHelper.checkData(data);
    }

    @Override
    public void onParseSuccess(Map<String, String> headers, String url, String from) {
        if (!TextUtils.isEmpty(from)) Notify.show(ResUtil.getString(R.string.parse_from, from));
        if (headers != null) headers.remove(HttpHeaders.RANGE);
        setMediaItem(headers, url);
    }

    @Override
    public void onParseError() {
        ErrorEvent.parse(tag);
    }

    @Override
    public void onIsPlayingChanged(boolean isPlaying) {
        PlayerEvent.playing(tag);
        ActionEvent.update();
    }

    @Override
    public void onPlaybackStateChanged(int state) {
        PlayerEvent.state(tag, state);
    }

    @Override
    public void onVideoSizeChanged(@NonNull VideoSize videoSize) {
        PlayerEvent.size(tag);
    }

    @Override
    public void onTracksChanged(@NonNull Tracks tracks) {
        if (tracks.isEmpty() || initTrack) return;
        setTrack(Track.find(getKey()));
        PlayerEvent.track(tag);
        initTrack = true;
    }

    @Override
    public void onPlayerError(@NonNull PlaybackException e) {
        if (++retry > 2) ErrorEvent.extract(tag, e.getErrorCodeName());
        else switch (e.errorCode) {
            case PlaybackException.ERROR_CODE_BEHIND_LIVE_WINDOW:
                seekToDefaultPosition();
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
                setFormat(ExoUtil.getMimeType(e.errorCode));
                break;
            default:
                ErrorEvent.extract(tag, e.getErrorCodeName());
                break;
        }
    }
}

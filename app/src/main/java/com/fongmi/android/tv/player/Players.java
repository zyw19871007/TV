package com.fongmi.android.tv.player;

import static androidx.media3.common.Player.COMMAND_SET_SPEED_AND_PITCH;

import android.app.Activity;
import android.content.Context;
import android.content.Intent;
import android.media.AudioManager;
import android.net.Uri;
import android.os.Bundle;
import android.text.TextUtils;

import androidx.annotation.NonNull;
import androidx.annotation.Nullable;
import androidx.media3.common.MediaItem;
import androidx.media3.common.MediaMetadata;
import androidx.media3.common.PlaybackException;
import androidx.media3.common.PlaybackParameters;
import androidx.media3.common.Player;
import androidx.media3.common.Tracks;
import androidx.media3.common.VideoSize;
import androidx.media3.session.MediaController;
import androidx.media3.ui.PlayerView;

import com.fongmi.android.tv.App;
import com.fongmi.android.tv.Constant;
import com.fongmi.android.tv.R;
import com.fongmi.android.tv.Setting;
import com.fongmi.android.tv.bean.Danmaku;
import com.fongmi.android.tv.bean.Drm;
import com.fongmi.android.tv.bean.Result;
import com.fongmi.android.tv.bean.Sub;
import com.fongmi.android.tv.bean.Track;
import com.fongmi.android.tv.event.ErrorEvent;
import com.fongmi.android.tv.event.PlayerEvent;
import com.fongmi.android.tv.impl.ParseCallback;
import com.fongmi.android.tv.player.danmaku.DanPlayer;
import com.fongmi.android.tv.player.exo.ErrorMsgProvider;
import com.fongmi.android.tv.player.exo.ExoUtil;
import com.fongmi.android.tv.player.exo.TrackUtil;
import com.fongmi.android.tv.server.Server;
import com.fongmi.android.tv.service.PlaybackService;
import com.fongmi.android.tv.utils.FileUtil;
import com.fongmi.android.tv.utils.ResUtil;
import com.fongmi.android.tv.utils.UrlUtil;
import com.fongmi.android.tv.utils.Util;
import com.github.catvod.utils.Path;
import com.google.common.net.HttpHeaders;
import com.orhanobut.logger.Logger;

import java.util.ArrayList;
import java.util.Arrays;
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

    private final ErrorMsgProvider provider;
    private final AudioManager audioManager;
    private final StringBuilder builder;
    private final Formatter formatter;
    private final Runnable runnable;

    private Map<String, String> headers;
    private List<Danmaku> danmakus;
    private MediaController controller;
    private DanPlayer danPlayer;
    private ParseJob parseJob;
    private PlayerView view;
    private VideoSize size;
    private List<Sub> subs;
    private String format;
    private String tag;
    private String key;
    private String url;
    private Drm drm;
    private Sub sub;
    private String metaTitle;
    private String metaArtist;
    private String metaArtUri;

    private boolean initTrack;
    private int retry;

    public static Players create(Activity activity) {
        Players player = new Players(activity);
        Server.get().setPlayer(player);
        return player;
    }

    private Players(Activity activity) {
        builder = new StringBuilder();
        provider = new ErrorMsgProvider();
        runnable = () -> ErrorEvent.timeout(tag);
        formatter = new Formatter(builder, Locale.getDefault());
        audioManager = (AudioManager) activity.getSystemService(Context.AUDIO_SERVICE);
    }

    public void setController(MediaController controller) {
        this.controller = controller;
        controller.addListener(this);
        if (view != null) view.setPlayer(controller);
    }

    public void init(PlayerView view) {
        if (controller != null) controller.clearMediaItems();
        setMediaItem();
        this.view = view;
        if (controller != null) view.setPlayer(controller);
    }

    public void setDanmakuView(DanmakuView view) {
        danPlayer = new DanPlayer();
        if (controller != null) danPlayer.setPlayer(controller);
        danPlayer.setView(view);
    }

    public MediaController get() {
        return controller;
    }

    public List<Danmaku> getDanmakus() {
        return danmakus;
    }

    public String getUrl() {
        return url;
    }

    public Map<String, String> getHeaders() {
        return headers == null ? new HashMap<>() : headers;
    }

    public void setSub(Sub sub) {
        this.sub = sub;
        setMediaItem();
    }

    public void setFormat(String format) {
        this.format = format;
        setMediaItem();
    }

    public String getKey() {
        return key != null ? key : url;
    }

    public void setKey(String key) {
        this.key = key;
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
        if (controller != null) controller.clearMediaItems();
    }

    public void clear() {
        danmakus = null;
        headers = null;
        format = null;
        subs = null;
        drm = null;
        url = null;
    }

    public String stringToTime(long time) {
        return Util.format(builder, formatter, time);
    }

    public int getVideoWidth() {
        return size == null ? 0 : size.width;
    }

    public int getVideoHeight() {
        return size == null ? 0 : size.height;
    }

    public float getSpeed() {
        return controller == null ? 1.0f : controller.getPlaybackParameters().speed;
    }

    public long getPosition() {
        return controller == null ? androidx.media3.common.C.TIME_UNSET : controller.getCurrentPosition();
    }

    public long getDuration() {
        return controller == null ? -1 : controller.getDuration();
    }

    public long getBuffered() {
        return controller == null ? 0 : controller.getBufferedPosition();
    }

    public boolean haveTrack(int type) {
        return controller != null && TrackUtil.count(controller.getCurrentTracks(), type) > 0;
    }

    public boolean haveDanmaku() {
        if (danmakus != null) for (Danmaku danmaku : danmakus) if (danmaku.isSelected()) return true;
        return false;
    }

    public boolean canSetOpening(long position, long duration) {
        return position > 0 && duration > 0 && position <= Constant.getOpEdLimit(duration);
    }

    public boolean canSetEnding(long position, long duration) {
        return position > 0 && duration > 0 && duration - position <= Constant.getOpEdLimit(duration);
    }

    public boolean isPlaying() {
        return controller != null && controller.isPlaying();
    }

    public boolean isEnded() {
        return controller != null && controller.getPlaybackState() == Player.STATE_ENDED;
    }

    public boolean isIdle() {
        return controller != null && controller.getPlaybackState() == Player.STATE_IDLE;
    }

    public boolean isEmpty() {
        return TextUtils.isEmpty(getUrl());
    }

    public boolean isLive() {
        return getDuration() < TimeUnit.MINUTES.toMillis(1) || (controller != null && controller.isCurrentMediaItemLive());
    }

    public boolean isVod() {
        return getDuration() > TimeUnit.MINUTES.toMillis(1) && (controller == null || !controller.isCurrentMediaItemLive());
    }

    public boolean isHard() {
        return PlaybackService.getDecode() == PlaybackService.HARD;
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
        return String.format(Locale.getDefault(), "%.2f", getSpeed());
    }

    public String getDecodeText() {
        return ResUtil.getStringArray(R.array.select_decode)[PlaybackService.getDecode()];
    }

    public String setSpeed(float speed) {
        if (controller == null || !controller.isCommandAvailable(COMMAND_SET_SPEED_AND_PITCH)) return getSpeedText();
        controller.setPlaybackParameters(controller.getPlaybackParameters().withSpeed(speed));
        return getSpeedText();
    }

    public String addSpeed() {
        float speed = getSpeed();
        float addon = speed >= 2 ? 1f : 0.25f;
        speed = speed >= 5 ? 0.25f : Math.min(speed + addon, 5.0f);
        return setSpeed(speed);
    }

    public String addSpeed(float value) {
        float speed = getSpeed();
        speed = Math.min(speed + value, 5);
        return setSpeed(speed);
    }

    public String subSpeed(float value) {
        float speed = getSpeed();
        speed = Math.max(speed - value, 0.25f);
        return setSpeed(speed);
    }

    public String toggleSpeed() {
        float speed = getSpeed();
        speed = speed == 1 ? Setting.getSpeed() : 1;
        return setSpeed(speed);
    }

    public void toggleDecode() {
        if (controller != null) {
            controller.sendCustomCommand(
                    new androidx.media3.session.SessionCommand(PlaybackService.ACTION_TOGGLE_DECODE, Bundle.EMPTY),
                    Bundle.EMPTY);
        }
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
        if (controller != null) controller.seekTo(time);
        if (danPlayer != null) danPlayer.seekTo(time);
    }

    public void seekToDefaultPosition() {
        if (controller != null) controller.seekToDefaultPosition();
        prepare();
    }

    public void prepare() {
        if (controller != null) controller.prepare();
    }

    public void play() {
        if (controller != null) controller.play();
        if (danPlayer != null) danPlayer.play();
    }

    public void pause() {
        if (controller != null) controller.pause();
        if (danPlayer != null) danPlayer.pause();
    }

    public void stop() {
        if (controller != null) controller.stop();
        if (danPlayer != null) danPlayer.stop();
        stopParse();
    }

    public void release() {
        stopParse();
        if (danPlayer != null) danPlayer.release();
        if (view != null) view.setPlayer(null);
        if (controller != null) {
            controller.removeListener(this);
            controller = null;
        }
        removeTimeoutCheck();
        Server.get().setPlayer(null);
        App.execute(() -> Source.get().stop());
        view = null;
        size = null;
    }

    private void removeTimeoutCheck() {
        App.removeCallbacks(runnable);
    }

    public void start(Result result, boolean useParse, long timeout) {
        if (result.getDrm() != null && !androidx.media3.exoplayer.drm.FrameworkMediaDrm.isCryptoSchemeSupported(result.getDrm().getUUID())) {
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
        drm = result.getDrm();
        subs = result.getSubs();
        format = result.getFormat();
        danmakus = result.getDanmaku();
        parseJob = ParseJob.create(this).start(result, useParse);
    }

    private void stopParse() {
        if (parseJob != null) parseJob.stop();
        parseJob = null;
    }

    private Map<String, String> checkUa(Map<String, String> headers) {
        for (Map.Entry<String, String> header : headers.entrySet()) if (HttpHeaders.USER_AGENT.equalsIgnoreCase(header.getKey())) return headers;
        headers.put(HttpHeaders.USER_AGENT, Setting.getUa().isEmpty() ? ExoUtil.getUa() : Setting.getUa());
        return headers;
    }

    private List<Sub> checkSub(List<Sub> subs) {
        if (subs == null) subs = this.subs = new ArrayList<>();
        if (sub == null || subs.contains(sub)) return subs;
        subs.add(0, sub);
        return subs;
    }

    public void setMediaItem() {
        if (url != null) setMediaItem(headers, url, format, drm, subs, danmakus, Constant.TIMEOUT_PLAY);
    }

    public void setMediaItem(String url) {
        setMediaItem(new HashMap<>(), url);
    }

    private void setMediaItem(Map<String, String> headers, String url) {
        setMediaItem(headers, url, format, drm, subs, danmakus, Constant.TIMEOUT_PLAY);
    }

    private void setMediaItem(Result result, long timeout) {
        setMediaItem(result.getHeader(), result.getRealUrl(), result.getFormat(), result.getDrm(), result.getSubs(), result.getDanmaku(), timeout);
    }

    private void setMediaItem(Map<String, String> headers, String url, String format, Drm drm, List<Sub> subs, List<Danmaku> danmakus, long timeout) {
        if (controller == null) return;
        MediaItem mediaItem = ExoUtil.getMediaItem(this.headers = checkUa(headers), UrlUtil.uri(this.url = url), this.format = format, this.drm = drm, checkSub(this.subs = subs), PlaybackService.getDecode());
        if (metaTitle != null || metaArtist != null || metaArtUri != null) {
            mediaItem = mediaItem.buildUpon().setMediaMetadata(buildMediaMetadata()).build();
        }
        controller.setMediaItem(mediaItem);
        Logger.t(TAG).d("headers=%s\nurl=%s\nformat=%s\ndrm=%s\nsubs=%s\ndanmakus=%s\ntimeout=%s", this.headers, url, format, drm, this.subs, danmakus, timeout);
        if (danPlayer != null) setDanmaku(this.danmakus = danmakus);
        App.post(runnable, timeout);
        PlayerEvent.prepare(tag);
        initTrack = false;
        prepare();
    }

    private MediaMetadata buildMediaMetadata() {
        MediaMetadata.Builder mb = new MediaMetadata.Builder();
        if (metaTitle != null) mb.setTitle(metaTitle);
        if (metaArtist != null) mb.setArtist(metaArtist);
        if (metaArtUri != null && !metaArtUri.isEmpty()) mb.setArtworkUri(Uri.parse(metaArtUri));
        return mb.build();
    }

    private void setDanmaku(List<Danmaku> items) {
        setDanmaku(items == null || items.isEmpty() ? Danmaku.empty() : items.get(0));
    }

    public void setDanmaku(Danmaku item) {
        danPlayer.setDanmaku(item);
        if (danmakus == null) danmakus = new ArrayList<>();
        if (!item.isEmpty() && !danmakus.contains(item)) danmakus.add(0, item);
        danmakus.forEach(d -> d.setSelected(d.getUrl().equals(item.getUrl())));
    }

    public void setDanmakuSize(float size) {
        if (danPlayer != null) danPlayer.setTextSize(size);
    }

    public void resetTrack() {
        if (controller != null) TrackUtil.reset(controller);
    }

    public void setTrack(List<Track> tracks) {
        if (controller != null && !tracks.isEmpty()) TrackUtil.setTrackSelection(controller, tracks);
    }

    private boolean isIllegal(String url) {
        Uri uri = UrlUtil.uri(url);
        String host = UrlUtil.host(uri);
        String scheme = UrlUtil.scheme(uri);
        if ("data".equals(scheme)) return false;
        return scheme.isEmpty() || "file".equals(scheme) ? !Path.exists(url) : host.isEmpty();
    }

    public void setMetadata(String title, String artist, String artUri) {
        this.metaTitle = title;
        this.metaArtist = artist;
        this.metaArtUri = artUri;
        if (controller == null || controller.getCurrentMediaItem() == null) return;
        MediaItem updated = controller.getCurrentMediaItem().buildUpon().setMediaMetadata(buildMediaMetadata()).build();
        controller.replaceMediaItem(0, updated);
    }

    public void share(Activity activity, CharSequence title) {
        try {
            if (isEmpty()) return;
            Bundle bundle = new Bundle();
            for (Map.Entry<String, String> entry : getHeaders().entrySet()) bundle.putString(entry.getKey(), entry.getValue());
            Intent intent = new Intent(Intent.ACTION_SEND);
            intent.addFlags(Intent.FLAG_ACTIVITY_NEW_TASK);
            intent.putExtra(Intent.EXTRA_TEXT, getUrl());
            intent.putExtra("extra_headers", bundle);
            intent.putExtra("title", title);
            intent.putExtra("name", title);
            intent.setType("text/plain");
            activity.startActivity(Util.getChooser(intent));
        } catch (Exception e) {
            e.printStackTrace();
        }
    }

    public void choose(Activity activity, CharSequence title) {
        try {
            if (isEmpty()) return;
            List<String> list = new ArrayList<>();
            for (Map.Entry<String, String> entry : getHeaders().entrySet()) list.addAll(Arrays.asList(entry.getKey(), entry.getValue()));
            Uri data = getUrl().startsWith("file://") || getUrl().startsWith("/") ? FileUtil.getShareUri(getUrl()) : Uri.parse(getUrl());
            Intent intent = new Intent(Intent.ACTION_VIEW);
            intent.addFlags(Intent.FLAG_ACTIVITY_NEW_TASK);
            intent.addFlags(Intent.FLAG_GRANT_READ_URI_PERMISSION);
            intent.setDataAndType(data, "video/*");
            intent.putExtra("title", title);
            intent.putExtra("return_result", isVod());
            intent.putExtra("headers", list.toArray(new String[0]));
            if (isVod()) intent.putExtra("position", (int) getPosition());
            activity.startActivityForResult(Util.getChooser(intent), 1001);
        } catch (Exception e) {
            e.printStackTrace();
        }
    }

    public void checkData(Intent data) {
        try {
            if (data == null || data.getExtras() == null) return;
            int position = data.getExtras().getInt("position", 0);
            String endBy = data.getExtras().getString("end_by", "");
            if ("playback_completion".equals(endBy)) com.fongmi.android.tv.event.ActionEvent.next();
            if ("user".equals(endBy)) seekTo(position);
        } catch (Exception e) {
            e.printStackTrace();
        }
    }

    @Override
    public void onParseSuccess(Map<String, String> headers, String url, String from) {
        if (!TextUtils.isEmpty(from)) com.fongmi.android.tv.utils.Notify.show(ResUtil.getString(R.string.parse_from, from));
        if (headers != null) headers.remove(HttpHeaders.RANGE);
        setMediaItem(headers, url);
    }

    @Override
    public void onParseError() {
        ErrorEvent.parse(tag);
    }

    @Override
    public void onIsPlayingChanged(boolean isPlaying) {
        if (isPlaying() && audioManager != null && audioManager.getMode() == AudioManager.MODE_IN_COMMUNICATION) pause();
        PlayerEvent.playing(tag);
    }

    @Override
    public void onPlaybackStateChanged(int state) {
        if (danPlayer != null) danPlayer.check(state);
        PlayerEvent.state(tag, state);
    }

    @Override
    public void onVideoSizeChanged(@NonNull VideoSize videoSize) {
        this.size = videoSize;
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
        if (++retry > 2) ErrorEvent.extract(tag, provider.get(e));
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
                ErrorEvent.extract(tag, provider.get(e));
                break;
        }
    }
}

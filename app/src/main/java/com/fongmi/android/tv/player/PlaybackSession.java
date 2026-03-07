package com.fongmi.android.tv.player;

import android.text.TextUtils;

import com.fongmi.android.tv.App;
import com.fongmi.android.tv.Constant;
import com.fongmi.android.tv.R;
import com.fongmi.android.tv.bean.Danmaku;
import com.fongmi.android.tv.bean.Drm;
import com.fongmi.android.tv.bean.Result;
import com.fongmi.android.tv.bean.Sub;
import com.fongmi.android.tv.impl.ParseCallback;
import com.fongmi.android.tv.utils.Notify;
import com.fongmi.android.tv.utils.ResUtil;
import com.google.common.net.HttpHeaders;

import java.util.HashMap;
import java.util.List;
import java.util.Map;

/**
 * Manages the media-loading pipeline for a single play session:
 * params storage, parse job, and timeout scheduling.
 * Calls back to {@link Listener} when media is ready or errors occur.
 */
class PlaybackSession implements ParseCallback {

    interface Listener {
        /** Called when a concrete URL + headers are ready to be loaded into the Core. */
        void onMediaReady(Map<String, String> headers, String url, List<Danmaku> danmakus, long timeout);
        void onParseError();
        void onTimeout();
    }

    private final Core core;
    private final Listener listener;
    private final PlaybackParams params;
    private final Runnable timeoutRunnable;
    private ParseJob parseJob;

    PlaybackSession(Core core, Listener listener) {
        this.core = core;
        this.listener = listener;
        this.params = new PlaybackParams();
        this.timeoutRunnable = () -> listener.onTimeout();
    }

    PlaybackParams getParams() {
        return params;
    }

    void start(Result result, boolean useParse, long timeout) {
        if (result.getParse() == 1 || result.getJx() == 1) {
            params.setParse(result.getFormat(), result.getDrm(), result.getSubs(), result.getDanmaku());
            stopParse();
            parseJob = ParseJob.create(this).start(result, useParse);
        } else {
            doLoad(result.getHeader(), result.getRealUrl(), result.getFormat(), result.getDrm(), result.getSubs(), result.getDanmaku(), timeout);
        }
    }

    void reload() {
        if (!params.isEmpty()) doLoad(params.getHeaders(), params.getUrl(), params.getFormat(), params.getDrm(), params.getRawSubs(), params.getDanmakus(), Constant.TIMEOUT_PLAY);
    }

    /** Loads a new URL keeping existing format/drm/subs (used by CastActivity direct URL load). */
    void loadUrl(String url) {
        doLoad(new HashMap<>(), url, params.getFormat(), params.getDrm(), params.getRawSubs(), params.getDanmakus(), Constant.TIMEOUT_PLAY);
    }

    void setFormat(String format) {
        params.setFormat(format);
        reload();
    }

    void setSub(Sub sub) {
        params.setSub(sub);
        reload();
    }

    void applyDanmaku(Danmaku item) {
        params.applyDanmaku(item);
    }

    void clear() {
        params.clear();
    }

    void cancelTimeout() {
        App.removeCallbacks(timeoutRunnable);
    }

    void stopParse() {
        if (parseJob != null) parseJob.stop();
        parseJob = null;
    }

    private void doLoad(Map<String, String> headers, String url, String format, Drm drm, List<Sub> subs, List<Danmaku> danmakus, long timeout) {
        params.setMedia(headers, url, format, drm, subs, danmakus);
        core.loadMedia(params.getHeaders(), url, format, drm, params.checkSub(subs), params.buildMediaMetadata(), core.getDecode());
        App.post(timeoutRunnable, timeout);
        listener.onMediaReady(params.getHeaders(), url, danmakus, timeout);
        core.prepare();
    }

    // ---- ParseCallback ----

    @Override
    public void onParseSuccess(Map<String, String> headers, String url, String from) {
        if (!TextUtils.isEmpty(from)) Notify.show(ResUtil.getString(R.string.parse_from, from));
        if (headers != null) headers.remove(HttpHeaders.RANGE);
        doLoad(headers, url, params.getFormat(), params.getDrm(), params.getRawSubs(), params.getDanmakus(), Constant.TIMEOUT_PLAY);
    }

    @Override
    public void onParseError() {
        listener.onParseError();
    }
}

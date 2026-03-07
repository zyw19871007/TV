package com.fongmi.android.tv.player;

import android.net.Uri;
import android.text.TextUtils;

import androidx.annotation.Nullable;
import androidx.media3.common.MediaMetadata;

import com.fongmi.android.tv.Setting;
import com.fongmi.android.tv.bean.Danmaku;
import com.fongmi.android.tv.bean.Drm;
import com.fongmi.android.tv.bean.Sub;
import com.fongmi.android.tv.player.exo.ExoUtil;
import com.google.common.net.HttpHeaders;

import java.util.ArrayList;
import java.util.HashMap;
import java.util.List;
import java.util.Map;

/** Mutable value object that holds all parameters describing the current/pending media item. */
public class PlaybackParams {

    Map<String, String> headers;
    List<Danmaku> danmakus;
    List<Sub> subs;
    String format;
    String url;
    String key;
    Drm drm;
    Sub sub;

    private String metaTitle;
    private String metaArtist;
    private String metaArtUri;

    // ---- public accessors ----

    public String getUrl() { return url; }

    public Map<String, String> getHeaders() { return headers == null ? new HashMap<>() : headers; }

    public List<Danmaku> getDanmakus() { return danmakus; }

    public String getKey() { return key != null ? key : url; }

    public void setKey(String key) { this.key = key; }

    public boolean isEmpty() { return TextUtils.isEmpty(url); }

    public boolean haveDanmaku() {
        if (danmakus != null) for (Danmaku d : danmakus) if (d.isSelected()) return true;
        return false;
    }

    public void clear() {
        danmakus = null;
        headers = null;
        format = null;
        subs = null;
        drm = null;
        sub = null;
        url = null;
    }

    public String getMetaTitle() { return metaTitle; }

    public String getMetaArtist() { return metaArtist; }

    public String getMetaArtUri() { return metaArtUri; }

    public void setMetadata(String title, String artist, String artUri) {
        metaTitle = title;
        metaArtist = artist;
        metaArtUri = artUri;
    }

    // ---- package-private setters (used only within player package) ----

    void setMedia(Map<String, String> headers, String url, String format, Drm drm, List<Sub> subs, List<Danmaku> danmakus) {
        this.headers = checkUa(headers);
        this.url = url;
        this.format = format;
        this.drm = drm;
        this.subs = subs;
        this.danmakus = danmakus;
    }

    void setParse(String format, Drm drm, List<Sub> subs, List<Danmaku> danmakus) {
        this.format = format;
        this.drm = drm;
        this.subs = subs;
        this.danmakus = danmakus;
    }

    void setSub(Sub sub) { this.sub = sub; }

    void setFormat(String format) { this.format = format; }

    void applyDanmaku(Danmaku item) {
        if (danmakus == null) danmakus = new ArrayList<>();
        if (!item.isEmpty() && !danmakus.contains(item)) danmakus.add(0, item);
        danmakus.forEach(d -> d.setSelected(d.getUrl().equals(item.getUrl())));
    }

    String getFormat() { return format; }

    Drm getDrm() { return drm; }

    List<Sub> getRawSubs() { return subs; }

    // ---- package-private helpers used during media item construction ----

    Map<String, String> checkUa(Map<String, String> headers) {
        for (Map.Entry<String, String> header : headers.entrySet())
            if (HttpHeaders.USER_AGENT.equalsIgnoreCase(header.getKey())) return headers;
        headers.put(HttpHeaders.USER_AGENT, Setting.getUa().isEmpty() ? ExoUtil.getUa() : Setting.getUa());
        return headers;
    }

    List<Sub> checkSub(List<Sub> subs) {
        if (subs == null) subs = this.subs = new ArrayList<>();
        if (sub == null || subs.contains(sub)) return subs;
        subs.add(0, sub);
        return subs;
    }

    @Nullable
    MediaMetadata buildMediaMetadata() {
        if (metaTitle == null && metaArtist == null && metaArtUri == null) return null;
        MediaMetadata.Builder b = new MediaMetadata.Builder();
        if (metaTitle != null) b.setTitle(metaTitle);
        if (metaArtist != null) b.setArtist(metaArtist);
        if (metaArtUri != null && !metaArtUri.isEmpty()) b.setArtworkUri(Uri.parse(metaArtUri));
        return b.build();
    }
}

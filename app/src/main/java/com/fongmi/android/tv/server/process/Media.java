package com.fongmi.android.tv.server.process;

import android.text.TextUtils;

import androidx.media3.common.MediaMetadata;
import androidx.media3.common.Player;
import androidx.media3.session.MediaSession;

import com.fongmi.android.tv.player.Players;
import com.fongmi.android.tv.server.Nano;
import com.fongmi.android.tv.server.Server;
import com.fongmi.android.tv.server.impl.Process;
import com.fongmi.android.tv.service.PlaybackService;
import com.google.gson.JsonObject;

import java.util.Map;
import java.util.Objects;

import fi.iki.elonen.NanoHTTPD.IHTTPSession;
import fi.iki.elonen.NanoHTTPD.Response;

public class Media implements Process {

    @Override
    public boolean isRequest(IHTTPSession session, String url) {
        return url.startsWith("/media");
    }

    @Override
    public Response doResponse(IHTTPSession session, String url, Map<String, String> files) {
        if (isNull()) return Nano.ok("{}");
        JsonObject result = new JsonObject();
        result.addProperty("url", getUrl());
        result.addProperty("state", getState());
        result.addProperty("speed", getSpeed());
        result.addProperty("title", getTitle());
        result.addProperty("artist", getArtist());
        result.addProperty("artwork", getArtUri());
        result.addProperty("duration", getDuration());
        result.addProperty("position", getPosition());
        return Nano.ok(result.toString());
    }

    private MediaSession getMediaSession() {
        return PlaybackService.getMediaSession();
    }

    private boolean isNull() {
        return Objects.isNull(getMediaSession()) || Objects.isNull(Server.get().getPlayer());
    }

    private Player getPlayer() {
        return getMediaSession().getPlayer();
    }

    private MediaMetadata getMetadata() {
        return getPlayer().getMediaMetadata();
    }

    private String getUrl() {
        Players p = Server.get().getPlayer();
        return p == null || TextUtils.isEmpty(p.getUrl()) ? "" : p.getUrl();
    }

    private String getTitle() {
        CharSequence title = getMetadata().title;
        return title == null ? "" : title.toString();
    }

    private String getArtist() {
        CharSequence artist = getMetadata().artist;
        return artist == null ? "" : artist.toString();
    }

    private String getArtUri() {
        return getMetadata().artworkUri != null ? getMetadata().artworkUri.toString() : "";
    }

    private long getDuration() {
        return getPlayer().getDuration();
    }

    private int getState() {
        return getPlayer().getPlaybackState();
    }

    private long getPosition() {
        return getPlayer().getCurrentPosition();
    }

    private float getSpeed() {
        return getPlayer().getPlaybackParameters().speed;
    }
}

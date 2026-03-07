package com.fongmi.android.tv.server.process;

import android.text.TextUtils;

import androidx.media3.common.Player;

import com.fongmi.android.tv.player.PlaybackState;
import com.fongmi.android.tv.server.Nano;
import com.fongmi.android.tv.server.Server;
import com.fongmi.android.tv.server.impl.Process;
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
        PlaybackState p = getPlayer();
        JsonObject result = new JsonObject();
        result.addProperty("url", TextUtils.isEmpty(p.getUrl()) ? "" : p.getUrl());
        result.addProperty("state", getState(p));
        result.addProperty("speed", p.getSpeed());
        result.addProperty("title", p.getMetaTitle() != null ? p.getMetaTitle() : "");
        result.addProperty("artist", p.getMetaArtist() != null ? p.getMetaArtist() : "");
        result.addProperty("artwork", p.getMetaArtUri() != null ? p.getMetaArtUri() : "");
        result.addProperty("duration", p.getDuration());
        result.addProperty("position", p.getPosition());
        return Nano.ok(result.toString());
    }

    private PlaybackState getPlayer() {
        return Server.get().getPlayer();
    }

    private boolean isNull() {
        return Objects.isNull(getPlayer()) || getPlayer().isEmpty();
    }

    // Map backend state to legacy PlaybackStateCompat int constants for HTTP API compatibility:
    // 0=none, 1=stopped, 2=paused, 3=playing, 6=buffering
    private int getState(PlaybackState p) {
        switch (p.getBackendPlaybackState()) {
            case Player.STATE_BUFFERING: return 6;
            case Player.STATE_READY:     return p.isPlaying() ? 3 : 2;
            case Player.STATE_ENDED:
            case Player.STATE_IDLE:      return 1;
            default:                     return 0;
        }
    }
}

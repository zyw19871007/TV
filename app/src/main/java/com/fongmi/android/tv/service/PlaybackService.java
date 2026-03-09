package com.fongmi.android.tv.service;

import static androidx.media3.exoplayer.DefaultRenderersFactory.EXTENSION_RENDERER_MODE_ON;
import static androidx.media3.exoplayer.DefaultRenderersFactory.EXTENSION_RENDERER_MODE_PREFER;

import android.content.Context;
import android.content.Intent;
import android.os.Bundle;

import androidx.annotation.NonNull;
import androidx.annotation.Nullable;
import androidx.core.content.ContextCompat;
import androidx.media3.common.AudioAttributes;
import androidx.media3.common.MediaItem;
import androidx.media3.common.Player;
import androidx.media3.exoplayer.ExoPlayer;
import androidx.media3.exoplayer.util.EventLogger;
import androidx.media3.session.MediaSession;
import androidx.media3.session.MediaSessionService;
import androidx.media3.session.SessionCommand;
import androidx.media3.session.SessionResult;

import com.fongmi.android.tv.BuildConfig;
import com.fongmi.android.tv.player.exo.ExoUtil;
import com.google.common.collect.ImmutableList;
import com.google.common.util.concurrent.Futures;
import com.google.common.util.concurrent.ListenableFuture;

import java.util.List;

public class PlaybackService extends MediaSessionService {

    public static final int SOFT = 0;
    public static final int HARD = 1;
    public static final String ACTION_TOGGLE_DECODE = BuildConfig.APPLICATION_ID + ".toggle_decode";

    private static volatile PlaybackService instance;
    private MediaSession mediaSession;
    private int decode = HARD;

    public static void start(Context context) {
        ContextCompat.startForegroundService(context, new Intent(context, PlaybackService.class));
    }

    public static void stop(Context context) {
        context.stopService(new Intent(context, PlaybackService.class));
    }

    public static boolean isRunning() {
        return instance != null;
    }

    @Nullable
    public static MediaSession getMediaSession() {
        return instance != null ? instance.mediaSession : null;
    }

    public static int getDecode() {
        return instance != null ? instance.decode : HARD;
    }

    @Override
    public void onCreate() {
        super.onCreate();
        instance = this;
        setMediaNotificationProvider(new CustomMediaNotificationProvider(this));
        mediaSession = new MediaSession.Builder(this, buildPlayer())
                .setCallback(new SessionCallback())
                .build();
    }

    private ExoPlayer buildPlayer() {
        int mode = decode == HARD ? EXTENSION_RENDERER_MODE_ON : EXTENSION_RENDERER_MODE_PREFER;
        ExoPlayer player = new ExoPlayer.Builder(this)
                .setLoadControl(ExoUtil.buildLoadControl())
                .setTrackSelector(ExoUtil.buildTrackSelector())
                .setRenderersFactory(ExoUtil.buildRenderersFactory(mode))
                .setMediaSourceFactory(ExoUtil.buildMediaSourceFactory())
                .build();
        if (BuildConfig.DEBUG) player.addAnalyticsListener(new EventLogger());
        player.setAudioAttributes(AudioAttributes.DEFAULT, true);
        player.setHandleAudioBecomingNoisy(true);
        player.setPlayWhenReady(true);
        return player;
    }

    void toggleDecode() {
        Player currentPlayer = mediaSession.getPlayer();
        MediaItem currentItem = currentPlayer.getCurrentMediaItem();
        long currentPosition = currentPlayer.getCurrentPosition();
        boolean wasReady = currentPlayer.getPlaybackState() != Player.STATE_IDLE;
        decode = decode == HARD ? SOFT : HARD;
        ExoPlayer newPlayer = buildPlayer();
        if (currentItem != null && wasReady) {
            newPlayer.setMediaItem(currentItem);
            newPlayer.seekTo(currentPosition);
            newPlayer.prepare();
        }
        mediaSession.setPlayer(newPlayer);
        currentPlayer.release();
    }

    @Nullable
    @Override
    public MediaSession onGetSession(@NonNull MediaSession.ControllerInfo controllerInfo) {
        return mediaSession;
    }

    @Override
    public void onTaskRemoved(@Nullable Intent rootIntent) {
        stopSelf();
    }

    @Override
    public void onDestroy() {
        instance = null;
        if (mediaSession != null) {
            mediaSession.getPlayer().release();
            mediaSession.release();
            mediaSession = null;
        }
        super.onDestroy();
    }

    private class SessionCallback implements MediaSession.Callback {

        @NonNull
        @Override
        public MediaSession.ConnectionResult onConnect(@NonNull MediaSession session, @NonNull MediaSession.ControllerInfo controller) {
            androidx.media3.session.SessionCommands sessionCommands = MediaSession.ConnectionResult.DEFAULT_SESSION_COMMANDS
                    .buildUpon()
                    .add(new SessionCommand(ACTION_TOGGLE_DECODE, Bundle.EMPTY))
                    .build();
            return MediaSession.ConnectionResult.accept(sessionCommands, MediaSession.ConnectionResult.DEFAULT_PLAYER_COMMANDS);
        }

        @NonNull
        @Override
        public ListenableFuture<List<MediaItem>> onAddMediaItems(@NonNull MediaSession session, @NonNull MediaSession.ControllerInfo controller, @NonNull List<MediaItem> mediaItems) {
            return Futures.immediateFuture(ImmutableList.copyOf(mediaItems));
        }

        @NonNull
        @Override
        public ListenableFuture<MediaSession.MediaItemsWithStartPosition> onSetMediaItems(@NonNull MediaSession session, @NonNull MediaSession.ControllerInfo controller, @NonNull List<MediaItem> mediaItems, int startIndex, long startPositionMs) {
            return Futures.immediateFuture(new MediaSession.MediaItemsWithStartPosition(ImmutableList.copyOf(mediaItems), startIndex, startPositionMs));
        }

        @NonNull
        @Override
        public ListenableFuture<SessionResult> onCustomCommand(@NonNull MediaSession session, @NonNull MediaSession.ControllerInfo controller, @NonNull SessionCommand customCommand, @NonNull Bundle args) {
            if (ACTION_TOGGLE_DECODE.equals(customCommand.customAction)) {
                toggleDecode();
                return Futures.immediateFuture(new SessionResult(SessionResult.RESULT_SUCCESS));
            }
            return Futures.immediateFuture(new SessionResult(SessionResult.RESULT_ERROR_NOT_SUPPORTED));
        }
    }
}

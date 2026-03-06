package com.fongmi.android.tv.service;

import android.content.Intent;
import android.os.Binder;
import android.os.IBinder;
import android.view.KeyEvent;

import androidx.annotation.NonNull;
import androidx.annotation.Nullable;
import androidx.core.content.ContextCompat;
import androidx.media3.common.Player;
import androidx.media3.exoplayer.ExoPlayer;
import androidx.media3.session.DefaultMediaNotificationProvider;
import androidx.media3.session.MediaSession;
import androidx.media3.session.MediaSessionService;
import androidx.media3.session.SessionResult;

import com.fongmi.android.tv.App;
import com.fongmi.android.tv.event.ActionEvent;
import com.fongmi.android.tv.player.Players;
import com.fongmi.android.tv.utils.Notify;
import com.google.common.util.concurrent.Futures;
import com.google.common.util.concurrent.ListenableFuture;

public class PlaybackService extends MediaSessionService {

    private static boolean sRunning;
    private Players players;
    private MediaSession mediaSession;

    public static boolean isRunning() {
        return sRunning;
    }

    // ---- Binder ----

    public class PlaybackBinder extends Binder {
        public Players getPlayers() { return players; }
    }

    @Override
    public IBinder onBind(Intent intent) {
        IBinder sessionBinder = super.onBind(intent);
        // Return our PlaybackBinder so Activities can access Players directly.
        // MediaSessionService.onBind() handles its own session token intents separately.
        return sessionBinder != null ? sessionBinder : new PlaybackBinder();
    }

    // ---- Lifecycle ----

    @Override
    public void onCreate() {
        super.onCreate();
        sRunning = true;
        setMediaNotificationProvider(
            new DefaultMediaNotificationProvider.Builder(this)
                .setChannelId(Notify.DEFAULT)
                .setNotificationId(Notify.ID)
                .build()
        );
        players = Players.create();
        players.buildExoPlayer();
        players.setOnExoPlayerRebuildListener(this::onExoPlayerRebuilt);
        createMediaSession(players.getExoPlayer());
    }

    private void createMediaSession(ExoPlayer player) {
        mediaSession = new MediaSession.Builder(this, player)
            .setCallback(new SessionCallbackImpl())
            .build();
    }

    private void onExoPlayerRebuilt(ExoPlayer newPlayer) {
        if (mediaSession != null) {
            mediaSession.release();
            mediaSession = null;
        }
        createMediaSession(newPlayer);
    }

    @Nullable
    @Override
    public MediaSession onGetSession(@NonNull MediaSession.ControllerInfo controllerInfo) {
        return mediaSession;
    }

    @Override
    public void onTaskRemoved(Intent rootIntent) {
        if (mediaSession == null || !mediaSession.getPlayer().isPlaying()) stopSelf();
    }

    @Override
    public void onDestroy() {
        sRunning = false;
        if (players != null) players.release();
        if (mediaSession != null) {
            mediaSession.release();
            mediaSession = null;
        }
        super.onDestroy();
    }

    // ---- Static helpers ----

    public static void start() {
        ContextCompat.startForegroundService(App.get(), new Intent(App.get(), PlaybackService.class));
    }

    public static void stop() {
        App.get().stopService(new Intent(App.get(), PlaybackService.class));
    }

    // ---- Session callback ----

    private static class SessionCallbackImpl implements MediaSession.Callback {

        @NonNull
        @Override
        public MediaSession.ConnectionResult onConnect(
                @NonNull MediaSession session,
                @NonNull MediaSession.ControllerInfo controller) {
            return MediaSession.ConnectionResult.accept(
                androidx.media3.session.SessionCommands.EMPTY,
                new Player.Commands.Builder()
                    .addAll(
                        Player.COMMAND_PLAY_PAUSE,
                        Player.COMMAND_SEEK_IN_CURRENT_MEDIA_ITEM,
                        Player.COMMAND_SEEK_TO_NEXT,
                        Player.COMMAND_SEEK_TO_PREVIOUS,
                        Player.COMMAND_STOP
                    )
                    .build()
            );
        }

        @NonNull
        @Override
        public ListenableFuture<SessionResult> onMediaButtonEvent(
                @NonNull MediaSession session,
                @NonNull MediaSession.ControllerInfo controllerInfo,
                @NonNull Intent mediaButtonIntent) {
            KeyEvent key = mediaButtonIntent.getParcelableExtra(Intent.EXTRA_KEY_EVENT);
            if (key != null && key.getAction() == KeyEvent.ACTION_DOWN) {
                switch (key.getKeyCode()) {
                    case KeyEvent.KEYCODE_MEDIA_NEXT:
                        ActionEvent.next();
                        return Futures.immediateFuture(new SessionResult(SessionResult.RESULT_SUCCESS));
                    case KeyEvent.KEYCODE_MEDIA_PREVIOUS:
                        ActionEvent.prev();
                        return Futures.immediateFuture(new SessionResult(SessionResult.RESULT_SUCCESS));
                    case KeyEvent.KEYCODE_MEDIA_STOP:
                        ActionEvent.stop();
                        return Futures.immediateFuture(new SessionResult(SessionResult.RESULT_SUCCESS));
                }
            }
            return MediaSession.Callback.super.onMediaButtonEvent(session, controllerInfo, mediaButtonIntent);
        }
    }
}

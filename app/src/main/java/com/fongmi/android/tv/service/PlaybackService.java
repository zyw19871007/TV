package com.fongmi.android.tv.service;

import android.app.ActivityManager;
import android.content.Context;
import android.content.Intent;
import android.view.KeyEvent;

import androidx.annotation.NonNull;
import androidx.annotation.Nullable;
import androidx.core.content.ContextCompat;
import androidx.media3.common.Player;
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

import org.greenrobot.eventbus.EventBus;
import org.greenrobot.eventbus.Subscribe;
import org.greenrobot.eventbus.ThreadMode;

import java.util.List;

public class PlaybackService extends MediaSessionService {

    private static Players player;
    private MediaSession mediaSession;

    public static void start(Players p) {
        player = p;
        ContextCompat.startForegroundService(App.get(), new Intent(App.get(), PlaybackService.class));
    }

    public static void stop() {
        App.get().stopService(new Intent(App.get(), PlaybackService.class));
    }

    @Override
    public void onCreate() {
        super.onCreate();
        EventBus.getDefault().register(this);
        setMediaNotificationProvider(
            new DefaultMediaNotificationProvider.Builder(this)
                .setChannelId(Notify.DEFAULT)
                .setNotificationId(Notify.ID)
                .build()
        );
        if (player != null && player.getExoPlayer() != null) {
            createMediaSession();
        }
    }

    private void createMediaSession() {
        mediaSession = new MediaSession.Builder(this, player.getExoPlayer())
            .setCallback(new SessionCallbackImpl())
            .build();
    }

    @Nullable
    @Override
    public MediaSession onGetSession(@NonNull MediaSession.ControllerInfo controllerInfo) {
        return mediaSession;
    }

    @Subscribe(threadMode = ThreadMode.MAIN)
    public void onActionEvent(ActionEvent event) {
        if (!event.isUpdate()) return;
        if (mediaSession == null && player != null && player.getExoPlayer() != null) {
            createMediaSession();
        }
    }

    @Override
    public void onTaskRemoved(Intent rootIntent) {
        if (player == null || !player.isPlaying()) stopSelf();
    }

    @Override
    public void onDestroy() {
        EventBus.getDefault().unregister(this);
        if (mediaSession != null) {
            mediaSession.release();
            mediaSession = null;
        }
        super.onDestroy();
    }

    public static boolean isRunning() {
        ActivityManager manager = (ActivityManager) App.get().getSystemService(Context.ACTIVITY_SERVICE);
        List<ActivityManager.RunningServiceInfo> services = manager.getRunningServices(Integer.MAX_VALUE);
        if (services == null || services.isEmpty()) return false;
        String clz = PlaybackService.class.getName();
        return services.stream().anyMatch(serviceInfo -> clz.equals(serviceInfo.service.getClassName()));
    }

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

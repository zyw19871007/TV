package com.fongmi.android.tv.service;

import android.app.Notification;
import android.content.Context;
import android.graphics.Bitmap;

import androidx.annotation.NonNull;
import androidx.annotation.Nullable;
import androidx.core.app.NotificationCompat;
import androidx.media3.common.MediaMetadata;
import androidx.media3.common.Player;
import androidx.media3.session.CommandButton;
import androidx.media3.session.MediaNotification;
import androidx.media3.session.MediaSession;
import androidx.palette.graphics.Palette;

import com.bumptech.glide.request.transition.Transition;
import com.fongmi.android.tv.R;
import com.fongmi.android.tv.event.ActionEvent;
import com.fongmi.android.tv.impl.CustomTarget;
import com.fongmi.android.tv.receiver.ActionReceiver;
import com.fongmi.android.tv.utils.ImgUtil;
import com.fongmi.android.tv.utils.Notify;
import com.google.common.collect.ImmutableList;

public class CustomMediaNotificationProvider implements MediaNotification.Provider {

    private final Context context;
    private String lastArtUri;
    private Bitmap lastArt;

    public CustomMediaNotificationProvider(Context context) {
        this.context = context;
    }

    @NonNull
    @Override
    public MediaNotification createNotification(@NonNull MediaSession session, @NonNull ImmutableList<CommandButton> customLayout, @NonNull MediaNotification.ActionFactory actionFactory, @NonNull Callback onNotificationChangedCallback) {
        loadArtwork(session, onNotificationChangedCallback);
        return new MediaNotification(Notify.ID, buildNotification(session, lastArt));
    }

    @Override
    public boolean handleCustomCommand(@NonNull MediaSession session, @NonNull String action, @NonNull android.os.Bundle extras) {
        return false;
    }

    private void loadArtwork(MediaSession session, Callback callback) {
        MediaMetadata metadata = session.getPlayer().getMediaMetadata();
        String artUri = metadata.artworkUri != null ? metadata.artworkUri.toString() : null;
        if (artUri == null || artUri.equals(lastArtUri)) return;
        lastArtUri = artUri;
        ImgUtil.load(artUri, new CustomTarget<>() {
            @Override
            public void onResourceReady(@NonNull Bitmap resource, @Nullable Transition<? super Bitmap> transition) {
                lastArt = resource;
                callback.onNotificationChanged(new MediaNotification(Notify.ID, buildNotification(session, resource)));
            }

            @Override
            public void onLoadFailed(@Nullable android.graphics.drawable.Drawable errorDrawable) {
                lastArt = null;
                callback.onNotificationChanged(new MediaNotification(Notify.ID, buildNotification(session, null)));
            }
        });
    }

    private Notification buildNotification(MediaSession session, @Nullable Bitmap art) {
        Player player = session.getPlayer();
        MediaMetadata metadata = player.getMediaMetadata();
        NotificationCompat.Builder builder = new NotificationCompat.Builder(context, Notify.DEFAULT);
        builder.setOngoing(false);
        builder.setColorized(true);
        builder.setOnlyAlertOnce(true);
        builder.setSmallIcon(R.drawable.ic_notification);
        builder.setVisibility(NotificationCompat.VISIBILITY_PUBLIC);
        builder.setDeleteIntent(ActionReceiver.getPendingIntent(context, ActionEvent.STOP));
        if (metadata.title != null) builder.setContentTitle(metadata.title);
        if (metadata.artist != null) builder.setContentText(metadata.artist);
        if (session.getSessionActivity() != null) builder.setContentIntent(session.getSessionActivity());
        if (art != null) setIconColor(builder, art);
        addActions(builder, player);
        return builder.build();
    }

    private void addActions(NotificationCompat.Builder builder, Player player) {
        builder.addAction(buildAction(androidx.media3.ui.R.drawable.exo_icon_previous, androidx.media3.ui.R.string.exo_controls_previous_description, ActionEvent.PREV));
        builder.addAction(getPlayPauseAction(player));
        builder.addAction(buildAction(androidx.media3.ui.R.drawable.exo_icon_next, androidx.media3.ui.R.string.exo_controls_next_description, ActionEvent.NEXT));
    }

    private NotificationCompat.Action buildAction(int icon, int title, String action) {
        return new NotificationCompat.Action(icon, context.getString(title), ActionReceiver.getPendingIntent(context, action));
    }

    private NotificationCompat.Action getPlayPauseAction(Player player) {
        if (player.isPlaying()) return buildAction(androidx.media3.ui.R.drawable.exo_icon_pause, androidx.media3.ui.R.string.exo_controls_pause_description, ActionEvent.PAUSE);
        return buildAction(androidx.media3.ui.R.drawable.exo_icon_play, androidx.media3.ui.R.string.exo_controls_play_description, ActionEvent.PLAY);
    }

    private void setIconColor(NotificationCompat.Builder builder, Bitmap art) {
        builder.setLargeIcon(art);
        Palette palette = Palette.from(art).generate();
        int white = androidx.core.content.ContextCompat.getColor(context, R.color.white);
        builder.setColor(palette.getMutedColor(palette.getVibrantColor(white)));
    }
}

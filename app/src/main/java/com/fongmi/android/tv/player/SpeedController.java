package com.fongmi.android.tv.player;

import static androidx.media3.common.Player.COMMAND_SET_SPEED_AND_PITCH;

import androidx.media3.exoplayer.ExoPlayer;

import com.fongmi.android.tv.Setting;

import java.util.Locale;

public class SpeedController {

    private ExoPlayer player;

    public void setPlayer(ExoPlayer player) {
        this.player = player;
    }

    public float getSpeed() {
        return player == null ? 1.0f : player.getPlaybackParameters().speed;
    }

    public String getSpeedText() {
        return String.format(Locale.getDefault(), "%.2f", getSpeed());
    }

    public String setSpeed(float speed) {
        if (player == null || !player.isCommandAvailable(COMMAND_SET_SPEED_AND_PITCH)) return getSpeedText();
        player.setPlaybackParameters(player.getPlaybackParameters().withSpeed(speed));
        return getSpeedText();
    }

    public String addSpeed() {
        float speed = getSpeed();
        float addon = speed >= 2 ? 1f : 0.25f;
        speed = speed >= 5 ? 0.25f : Math.min(speed + addon, 5.0f);
        return setSpeed(speed);
    }

    public String addSpeed(float value) {
        return setSpeed(Math.min(getSpeed() + value, 5));
    }

    public String subSpeed(float value) {
        return setSpeed(Math.max(getSpeed() - value, 0.25f));
    }

    public String toggleSpeed() {
        float speed = getSpeed();
        return setSpeed(speed == 1 ? Setting.getSpeed() : 1);
    }
}

package com.fongmi.android.tv.player;

public interface PlayerListener {
    void onPrepare(String tag);
    void onPlaying(String tag);
    void onState(String tag, int state);
    void onTrack(String tag);
    void onSize(String tag);
    void onError(String tag, String msg);
    void onUpdate();
}

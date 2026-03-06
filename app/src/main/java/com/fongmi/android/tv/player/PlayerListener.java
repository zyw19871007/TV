package com.fongmi.android.tv.player;

public interface PlayerListener {
    void onPrepare();
    void onPlaying();
    void onState(int state);
    void onTrack();
    void onSize();
    void onError(String msg);
}

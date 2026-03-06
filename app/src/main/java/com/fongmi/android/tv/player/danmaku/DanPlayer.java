package com.fongmi.android.tv.player.danmaku;

import androidx.annotation.NonNull;
import androidx.media3.common.Player;

import com.fongmi.android.tv.App;
import com.fongmi.android.tv.bean.Danmaku;
import com.fongmi.android.tv.utils.ResUtil;
import com.github.catvod.net.OkHttp;

import java.util.HashMap;
import java.util.Map;
import java.util.concurrent.Future;

import master.flame.danmaku.controller.DrawHandler;
import master.flame.danmaku.danmaku.model.BaseDanmaku;
import master.flame.danmaku.danmaku.model.DanmakuTimer;
import master.flame.danmaku.danmaku.model.IDisplayer;
import master.flame.danmaku.danmaku.model.android.DanmakuContext;
import master.flame.danmaku.ui.widget.DanmakuView;

public class DanPlayer implements DrawHandler.Callback, Player.Listener {

    private final DanmakuContext context;
    private DanmakuView view;
    private Future<?> future;
    private Player player;

    public DanPlayer() {
        context = DanmakuContext.create();
        initContext();
    }

    private void initContext() {
        Map<Integer, Integer> lines = new HashMap<>();
        lines.put(BaseDanmaku.TYPE_FIX_TOP, 2);
        lines.put(BaseDanmaku.TYPE_SCROLL_RL, 2);
        lines.put(BaseDanmaku.TYPE_SCROLL_LR, 2);
        lines.put(BaseDanmaku.TYPE_FIX_BOTTOM, 2);
        context.setScaleTextSize(0.8f);
        context.setMaximumLines(lines);
        context.setScrollSpeedFactor(1.2f);
        context.setDanmakuTransparency(0.8f);
        context.setDanmakuMargin(ResUtil.dp2px(8));
        context.setDanmakuStyle(IDisplayer.DANMAKU_STYLE_STROKEN, 3);
    }

    public void setView(DanmakuView view) {
        view.setCallback(this);
        this.view = view;
    }

    public void setPlayer(Player player) {
        if (this.player != null) this.player.removeListener(this);
        this.player = player;
        if (player != null) {
            context.setDanmakuSync(new Sync(player));
            player.addListener(this);
        }
    }

    private boolean isPrepared() {
        return view != null && view.isPrepared();
    }

    public DanPlayer cancel() {
        if (future == null) return this;
        OkHttp.cancel("danmaku");
        future.cancel(true);
        future = null;
        return this;
    }

    public void seekTo(long time) {
        App.execute(() -> {
            if (!isPrepared()) return;
            view.seekTo(time);
            view.hide();
        });
    }

    public void play() {
        App.execute(() -> {
            if (isPrepared()) view.resume();
        });
    }

    public void pause() {
        App.execute(() -> {
            if (isPrepared()) view.pause();
        });
    }

    public void stop() {
        cancel();
        App.execute(() -> {
            if (view != null) view.stop();
        });
    }

    public void release() {
        if (player != null) {
            player.removeListener(this);
            player = null;
        }
        cancel();
        App.execute(() -> {
            if (view != null) view.release();
        });
    }

    public void setDanmaku(Danmaku item) {
        cancel();
        future = App.submit(() -> {
            if (view != null) view.release();
            if (item.isEmpty() || view == null) return;
            view.prepare(new Parser().load(new Loader().load(item).getDataSource()), context);
        });
    }

    public void setTextSize(float size) {
        context.setScaleTextSize(size);
    }

    // Player.Listener — 自律同步彈幕，不再需要 Players 代轉呼叫

    @Override
    public void onIsPlayingChanged(boolean isPlaying) {
        if (isPlaying) play();
        else pause();
    }

    @Override
    public void onPlaybackStateChanged(int state) {
        if (state == Player.STATE_BUFFERING) pause();
        else if (state == Player.STATE_READY) prepared();
        else if (state == Player.STATE_IDLE || state == Player.STATE_ENDED) stop();
    }

    @Override
    public void onPositionDiscontinuity(
            @NonNull Player.PositionInfo oldPosition,
            @NonNull Player.PositionInfo newPosition,
            @Player.DiscontinuityReason int reason) {
        if (reason == Player.DISCONTINUITY_REASON_SEEK) {
            seekTo(newPosition.positionMs);
        }
    }

    // DrawHandler.Callback

    @Override
    public void prepared() {
        App.post(() -> {
            if (player == null) return;
            boolean playing = player.isPlaying();
            long position = player.getCurrentPosition();
            App.execute(() -> {
                if (!isPrepared()) return;
                if (playing) view.start(position);
                else view.pause();
                view.show();
            });
        });
    }

    @Override
    public void updateTimer(DanmakuTimer danmakuTimer) {
    }

    @Override
    public void danmakuShown(BaseDanmaku baseDanmaku) {
    }

    @Override
    public void drawingFinished() {
    }
}

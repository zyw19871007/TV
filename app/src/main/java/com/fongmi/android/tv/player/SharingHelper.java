package com.fongmi.android.tv.player;

import android.app.Activity;
import android.content.Intent;
import android.net.Uri;
import android.os.Bundle;

import com.fongmi.android.tv.event.ActionEvent;
import com.fongmi.android.tv.utils.FileUtil;
import com.fongmi.android.tv.utils.Util;

import java.util.ArrayList;
import java.util.Arrays;
import java.util.List;
import java.util.Map;

public class SharingHelper {

    private final Players players;

    public SharingHelper(Players players) {
        this.players = players;
    }

    public void share(Activity activity, CharSequence title) {
        try {
            if (players.isEmpty()) return;
            Bundle bundle = new Bundle();
            for (Map.Entry<String, String> entry : players.getHeaders().entrySet()) bundle.putString(entry.getKey(), entry.getValue());
            Intent intent = new Intent(Intent.ACTION_SEND);
            intent.addFlags(Intent.FLAG_ACTIVITY_NEW_TASK);
            intent.putExtra(Intent.EXTRA_TEXT, players.getUrl());
            intent.putExtra("extra_headers", bundle);
            intent.putExtra("title", title);
            intent.putExtra("name", title);
            intent.setType("text/plain");
            activity.startActivity(Util.getChooser(intent));
        } catch (Exception e) {
            e.printStackTrace();
        }
    }

    public void choose(Activity activity, CharSequence title) {
        try {
            if (players.isEmpty()) return;
            List<String> list = new ArrayList<>();
            for (Map.Entry<String, String> entry : players.getHeaders().entrySet()) list.addAll(Arrays.asList(entry.getKey(), entry.getValue()));
            String url = players.getUrl();
            Uri data = url.startsWith("file://") || url.startsWith("/") ? FileUtil.getShareUri(url) : Uri.parse(url);
            Intent intent = new Intent(Intent.ACTION_VIEW);
            intent.addFlags(Intent.FLAG_ACTIVITY_NEW_TASK);
            intent.addFlags(Intent.FLAG_GRANT_READ_URI_PERMISSION);
            intent.setDataAndType(data, "video/*");
            intent.putExtra("title", title);
            intent.putExtra("return_result", players.isVod());
            intent.putExtra("headers", list.toArray(new String[0]));
            if (players.isVod()) intent.putExtra("position", (int) players.getPosition());
            activity.startActivityForResult(Util.getChooser(intent), 1001);
        } catch (Exception e) {
            e.printStackTrace();
        }
    }

    public void checkData(Intent data) {
        try {
            if (data == null || data.getExtras() == null) return;
            int position = data.getExtras().getInt("position", 0);
            String endBy = data.getExtras().getString("end_by", "");
            if ("playback_completion".equals(endBy)) ActionEvent.next();
            if ("user".equals(endBy)) players.seekTo(position);
        } catch (Exception e) {
            e.printStackTrace();
        }
    }
}

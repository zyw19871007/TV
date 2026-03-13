package com.fongmi.android.tv.ui.activity;

import com.fongmi.android.tv.App;
import com.fongmi.android.tv.api.config.VodConfig;
import com.fongmi.android.tv.bean.History;
import com.fongmi.android.tv.db.AppDatabase;

import org.json.JSONArray;
import org.json.JSONObject;

import java.text.ParseException;
import java.text.SimpleDateFormat;
import java.util.ArrayList;
import java.util.Date;
import java.util.HashMap;
import java.util.List;
import java.util.Map;
import java.util.TimeZone;

import okhttp3.MediaType;
import okhttp3.OkHttpClient;
import okhttp3.Request;
import okhttp3.RequestBody;
import okhttp3.Response;

public class ShadowUtil {
    // 1. 定义你的 Bearer Token（建议抽离到配置类，不要硬编码）
    private static final String BEARER_TOKEN = "eyJhbGciOiJIUzI1NiIsInR5cCI6IkpXVCJ9.eyJzdWIiOiJzaGFkb3cifQ.n6boJtk7dGQGWLI64-8uZaCFRPIC9MCb_PdCJGEFkuo";

    // 2. 复用的 OkHttpClient 实例（如果你的 OkHttp 工具类已封装，可忽略）
    private static final OkHttpClient OK_HTTP_CLIENT = new OkHttpClient();

    // 解析ISO 8601格式的时间字符串为毫秒
    private static long parseIso8601ToMillis(String iso8601String) {
        if (iso8601String == null || iso8601String.isEmpty()) {
            return 0;
        }
        try {
            // 处理ISO 8601格式：2026-03-12T15:32:20.880298
            SimpleDateFormat sdf = new SimpleDateFormat("yyyy-MM-dd'T'HH:mm:ss.SSSSSS");
            sdf.setTimeZone(TimeZone.getTimeZone("Asia/Shanghai"));
            Date date = sdf.parse(iso8601String);
            return date.getTime();
        } catch (ParseException e) {
            e.printStackTrace();
            return 0;
        }
    }

    public static void insertHistoryFromUrl() {
        App.execute(()->{
            List<History> his_list = new ArrayList<>();
            try {
                String url = "http://47.93.13.56:11266/api/play-history";
                Request request = new Request.Builder()
                        .url(url)
                        // 核心：添加 Authorization Header
                        .header("Authorization", "Bearer " + BEARER_TOKEN)
                        .header("Content-Type", "application/json; charset=utf-8")
                        .get()
                        .build();
                Response response = OK_HTTP_CLIENT.newCall(request).execute();
                Map<String, String> video_map = new HashMap<>();
                if (response.isSuccessful()) {
                    JSONObject json = new JSONObject(response.body().string());
                    JSONArray items = json.getJSONObject("data").getJSONArray("items");
                    for (int i = 0; i < items.length(); i++) {
                        JSONObject item = items.getJSONObject(i);
                        String video_id = item.optString("video_id");
                        if(video_map.containsKey(video_id)) continue;
                        video_map.put(video_id, video_id);
                        History history = new History();

                        history.setCid(VodConfig.getCid());
                        history.setVodName(item.optString("title"));
                        history.setVodFlag(item.optString("source"));
                        history.setVodRemarks(item.optString("episode"));
                        history.setPosition((long) (item.optDouble("watch_time", 0) *1000 ));
                        // 时间格式转换 "create_time": "2026-03-12T15:32:20.880298" 转换为毫秒
                        history.setCreateTime(parseIso8601ToMillis(item.optString("create_time")));
                        history.setKey("shadow".concat(AppDatabase.SYMBOL).concat(item.optString("video_id")).concat(AppDatabase.SYMBOL) + VodConfig.getCid());
                        history.save();
                        his_list.add(history);
                    }
                }
            } catch (Exception e) {
                e.printStackTrace();
            }
        });
    }

    public static void saveHistory(History mHistory) {
        try {
            String url = "http://47.93.13.56:11266/api/play-history";
            JSONObject json = new JSONObject();
            json.put("video_id", mHistory.getVodId());
            json.put("title", mHistory.getVodName());
            json.put("source", mHistory.getVodFlag());
            json.put("episode", mHistory.getVodRemarks());
            json.put("watch_time", mHistory.getPosition() / 1000.0);

            // 构建 JSON 请求体
            RequestBody body = RequestBody.create(
                    json.toString(),
                    MediaType.get("application/json; charset=utf-8"));

            // ========== 核心修改：添加 Authorization Header ==========
            // // 方式1：如果你的 OkHttp 工具类支持传入 Header Map（适配原有调用方式）
            // Map<String, String> headers = new HashMap<>();
            // // 关键：Authorization Header 格式（Bearer + 空格 + Token）
            // headers.put("Authorization", "Bearer " + BEARER_TOKEN);
            // // 可选：添加其他通用 Header
            // headers.put("Content-Type", "application/json; charset=utf-8");
            // // 调用工具类（替换原有 null 为 headers）
            // OkHttp.newCall(url, headers, body).execute();

            // ========== 备用方案：原生 OkHttp 写法（如果工具类不支持 Header） ==========

            Request request = new Request.Builder()
                    .url(url)
                    // 核心：添加 Authorization Header
                    .header("Authorization", "Bearer " + BEARER_TOKEN)
                    .header("Content-Type", "application/json; charset=utf-8")
                    .post(body)
                    .build();

            // 执行请求（建议用 try-with-resources 自动关闭响应）
            try (Response response = OK_HTTP_CLIENT.newCall(request).execute()) {
                if (response.isSuccessful()) {
                    // 请求成功处理逻辑
                    System.out.println("播放记录提交成功：" + response.body().string());
                } else {
                    // 请求失败处理
                    System.err.println("播放记录提交失败，状态码：" + response.code());
                }
            }

        } catch (Exception e) {
            // 补充异常处理（原有代码缺失，建议添加）
            e.printStackTrace();
            System.err.println("提交播放记录异常：" + e.getMessage());
        }

    }

}

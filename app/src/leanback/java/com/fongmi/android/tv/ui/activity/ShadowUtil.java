package com.fongmi.android.tv.ui.activity;

import com.fongmi.android.tv.bean.History;

import org.json.JSONObject;

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

    public static void saveHistory(History mHistory) {
        try {
            String url = "http://47.93.13.56:11266/api/play-history";
            JSONObject json = new JSONObject();
            json.put("title", mHistory.getVodName());
            json.put("source", mHistory.getVodFlag());
            json.put("episode", mHistory.getVodRemarks());
            json.put("watch_time", mHistory.getPosition() / 1000.0);

            // 构建 JSON 请求体
            RequestBody body = RequestBody.create(
                    json.toString(),
                    MediaType.get("application/json; charset=utf-8")
            );

            // ========== 核心修改：添加 Authorization Header ==========
//            // 方式1：如果你的 OkHttp 工具类支持传入 Header Map（适配原有调用方式）
//            Map<String, String> headers = new HashMap<>();
//            // 关键：Authorization Header 格式（Bearer + 空格 + Token）
//            headers.put("Authorization", "Bearer " + BEARER_TOKEN);
//            // 可选：添加其他通用 Header
//            headers.put("Content-Type", "application/json; charset=utf-8");
//            // 调用工具类（替换原有 null 为 headers）
//            OkHttp.newCall(url, headers, body).execute();

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

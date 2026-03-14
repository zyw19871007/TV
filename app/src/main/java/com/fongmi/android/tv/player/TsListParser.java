package com.fongmi.android.tv.player;

import androidx.annotation.NonNull;
import com.orhanobut.logger.Logger;
import java.io.BufferedReader;
import java.io.InputStream;
import java.io.InputStreamReader;
import java.net.HttpURLConnection;
import java.net.URL;
import java.util.ArrayList;
import java.util.List;
import java.util.Map;

public class TsListParser {

    private static final String TAG = "TsListParser";
    private static final double AD_DURATION_THRESHOLD = 30.0; // 广告判定阈值（秒）
    private final String m3u8Url;
    private final Map<String, String> headers;

    // 仅保留核心构造器
    public TsListParser(String m3u8Url, Map<String, String> headers) {
        this.m3u8Url = m3u8Url;
        this.headers = headers == null ? Map.of() : headers;
    }

    /**
     * 核心：解析并提取广告TS分片列表（仅保留广告切片）
     * @return 广告TS分片列表
     */
    public List<TsSegment> parseAdTsSegments() {
        List<TsSegment> allSegments = parseTsListWithAdMark();
        List<TsSegment> adSegments = new ArrayList<>();
        for (TsSegment segment : allSegments) {
            if (segment.isAd()) {
                adSegments.add(segment);
            }
        }
        return adSegments;
    }

    /**
     * 基础解析：提取带广告标记的所有TS分片（供广告筛选和跳过逻辑使用）
     */
    private List<TsSegment> parseTsListWithAdMark() {
        List<TsSegment> tsSegments = new ArrayList<>();
        if (!isM3u8Url(m3u8Url)) {
            Logger.w(TAG, "Url is not m3u8: %s", m3u8Url);
            return tsSegments;
        }

        String m3u8Content = getM3u8Content();
        if (m3u8Content.isEmpty()) {
            Logger.e(TAG, "Get m3u8 content failed: %s", m3u8Url);
            return tsSegments;
        }

        tsSegments = parseM3u8ContentWithAdMark(m3u8Content);
        return tsSegments;
    }

    // 校验是否为m3u8链接
    private boolean isM3u8Url(String url) {
        return url != null && (url.endsWith(".m3u8") || url.contains(".m3u8?") || url.contains("m3u8/"));
    }

    // 仅保留获取m3u8内容的核心逻辑
    private String getM3u8Content() {
        HttpURLConnection conn = null;
        InputStream is = null;
        BufferedReader br = null;
        StringBuilder content = new StringBuilder();
        try {
            URL url = new URL(m3u8Url);
            conn = (HttpURLConnection) url.openConnection();
            // 设置请求头
            for (Map.Entry<String, String> entry : headers.entrySet()) {
                conn.setRequestProperty(entry.getKey(), entry.getValue());
            }
            conn.setConnectTimeout(5000);
            conn.setReadTimeout(10000);
            conn.setRequestMethod("GET");

            if (conn.getResponseCode() != HttpURLConnection.HTTP_OK) {
                Logger.e(TAG, "M3u8 request failed, code: %d", conn.getResponseCode());
                return "";
            }

            is = conn.getInputStream();
            br = new BufferedReader(new InputStreamReader(is, "UTF-8"));
            String line;
            while ((line = br.readLine()) != null) {
                content.append(line).append("\n");
            }
        } catch (Exception e) {
            Logger.e(TAG, "Get m3u8 content exception", e);
        } finally {
            try {
                if (br != null) br.close();
                if (is != null) is.close();
                if (conn != null) conn.disconnect();
            } catch (Exception e) {
                Logger.e(TAG, "Close stream failed", e);
            }
        }
        return content.toString();
    }

    // 仅保留解析广告标记的核心逻辑
    private List<TsSegment> parseM3u8ContentWithAdMark(String m3u8Content) {
        List<TsSegment> tsSegments = new ArrayList<>();
        List<Integer> discontinuityIndices = new ArrayList<>();
        String[] lines = m3u8Content.split("\n");
        String baseUrl = getBaseUrl(m3u8Url);
        double currentTsDuration = 0.0;
        boolean hasDiscontinuity = false;

        for (String line : lines) {
            line = line.trim();
            if (line.isEmpty()) continue;

            // 处理分片分隔标记
            if (line.equals("#EXT-X-DISCONTINUITY")) {
                hasDiscontinuity = true;
                continue;
            }

            // 提取分片时长
            if (line.startsWith("#EXTINF:")) {
                currentTsDuration = parseExtInfDuration(line);
                continue;
            }

            // 跳过注释行
            if (line.startsWith("#")) continue;

            // 拼接TS完整URL
            String tsUrl = getFullTsUrl(baseUrl, line);
            if (tsUrl.isEmpty()) continue;

            // 创建分片对象
            TsSegment segment = new TsSegment(tsUrl, currentTsDuration);
            tsSegments.add(segment);

            // 记录分隔符后的分片索引
            if (hasDiscontinuity) {
                discontinuityIndices.add(tsSegments.size() - 1);
                hasDiscontinuity = false;
            }
            currentTsDuration = 0.0;
        }

        // 标记广告分片（核心逻辑）
        markAdSegments(tsSegments, discontinuityIndices);
        // 计算分片时间范围（供广告跳过使用）
        calculateSegmentTimeRange(tsSegments);

        return tsSegments;
    }

    // 解析EXTINF时长
    private double parseExtInfDuration(String extInfLine) {
        try {
            String durationStr = extInfLine.substring("#EXTINF:".length()).split(",")[0].trim();
            return Double.parseDouble(durationStr);
        } catch (Exception e) {
            Logger.e(TAG, "Parse EXTINF duration failed: %s", extInfLine);
            return 0.0;
        }
    }

    // 标记广告分片（核心逻辑）
    private void markAdSegments(List<TsSegment> tsSegments, List<Integer> discontinuityIndices) {
        List<Integer> groupIndices = new ArrayList<>();
        groupIndices.add(0);
        groupIndices.addAll(discontinuityIndices);
        groupIndices.add(tsSegments.size());

        for (int i = 0; i < groupIndices.size() - 1; i++) {
            int groupStartIdx = groupIndices.get(i);
            int groupEndIdx = groupIndices.get(i + 1);

            // 计算分组总时长
            double groupTotalDuration = 0.0;
            for (int j = groupStartIdx; j < groupEndIdx && j < tsSegments.size(); j++) {
                groupTotalDuration += tsSegments.get(j).getDuration();
            }

            // 短于阈值标记为广告
            if (groupTotalDuration > 0 && groupTotalDuration < AD_DURATION_THRESHOLD) {
                for (int j = groupStartIdx; j < groupEndIdx && j < tsSegments.size(); j++) {
                    tsSegments.get(j).setAd(true);
                }
            }
        }
    }

    // 计算分片时间范围（供广告跳过使用）
    private void calculateSegmentTimeRange(List<TsSegment> tsSegments) {
        double currentProgress = 0.0;
        for (TsSegment segment : tsSegments) {
            segment.setStartTime(currentProgress);
            currentProgress += segment.getDuration();
            segment.setEndTime(currentProgress);
        }
    }

    // 获取m3u8基础URL
    @NonNull
    private String getBaseUrl(String m3u8Url) {
        if (m3u8Url.contains("/")) {
            return m3u8Url.substring(0, m3u8Url.lastIndexOf("/") + 1);
        }
        return m3u8Url;
    }

    // 拼接TS完整URL
    private String getFullTsUrl(String baseUrl, String tsPath) {
        if (tsPath.startsWith("http://") || tsPath.startsWith("https://")) {
            return tsPath;
        }
        return baseUrl + tsPath;
    }

    /**
     * 【核心保留】在播放进度变化时检测并跳过广告
     */
    public static void handleAdSkipOnTimelineChanged(@NonNull Players player, @NonNull List<TsSegment> tsSegments) {
        if (tsSegments.isEmpty() || player.isEmpty()) {
            Logger.w(TAG, "Ad skip: tsSegments is empty or player is invalid");
            return;
        }

        try {
            double currentProgress = player.get().getCurrentPosition() / 1000.0;
            TsSegment currentAdSegment = findCurrentAdSegment(tsSegments, currentProgress);
            if (currentAdSegment != null) {
                double adGroupEndTime = findAdGroupEndTime(tsSegments, currentAdSegment);
                long skipToPosition = (long) (adGroupEndTime * 1000);
                if (Math.abs(skipToPosition - player.get().getCurrentPosition()) > 1000) {
                    Logger.d(TAG, "Skip ad: current=%fs, skip to=%fs (ms=%d)",
                            currentProgress, adGroupEndTime, skipToPosition);
                    player.seekTo(skipToPosition);
                }
            }
        } catch (Exception e) {
            Logger.e(TAG, "Ad skip exception", e);
        }
    }

    // 查找当前进度所在的广告分片
    private static TsSegment findCurrentAdSegment(List<TsSegment> tsSegments, double currentProgress) {
        for (TsSegment segment : tsSegments) {
            if (segment.isAd() && currentProgress >= segment.getStartTime() && currentProgress < segment.getEndTime()) {
                return segment;
            }
        }
        return null;
    }

    // 查找广告组的最后结束时间
    private static double findAdGroupEndTime(List<TsSegment> tsSegments, TsSegment startAdSegment) {
        double adGroupEndTime = startAdSegment.getEndTime();
        int startIndex = tsSegments.indexOf(startAdSegment);

        for (int i = startIndex + 1; i < tsSegments.size(); i++) {
            TsSegment nextSegment = tsSegments.get(i);
            if (nextSegment.isAd()) {
                adGroupEndTime = nextSegment.getEndTime();
            } else {
                break;
            }
        }
        return adGroupEndTime;
    }

    /**
     * 核心数据模型：仅保留广告跳过所需属性
     */
    public static class TsSegment {
        private final String url;
        private final double duration;
        private boolean isAd;
        private double startTime;
        private double endTime;

        public TsSegment(String url, double duration) {
            this.url = url;
            this.duration = duration;
            this.isAd = false;
            this.startTime = 0.0;
            this.endTime = 0.0;
        }

        // 仅保留核心Getter/Setter
        public String getUrl() { return url; }
        public double getDuration() { return duration; }
        public boolean isAd() { return isAd; }
        public void setAd(boolean ad) { isAd = ad; }
        public double getStartTime() { return startTime; }
        public void setStartTime(double startTime) { this.startTime = startTime; }
        public double getEndTime() { return endTime; }
        public void setEndTime(double endTime) { this.endTime = endTime; }
    }

    // 静态快捷方法：获取广告分片列表
    public static List<TsSegment> getAdTsSegments(Players player) {
        if (player == null || player.isEmpty()) {
            return new ArrayList<>();
        }
        TsListParser parser = new TsListParser(player.getUrl(), player.getHeaders());
        return parser.parseAdTsSegments();
    }
}
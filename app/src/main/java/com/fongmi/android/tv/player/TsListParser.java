package com.fongmi.android.tv.player;

import androidx.annotation.NonNull;

import com.fongmi.android.tv.App;
import com.orhanobut.logger.Logger;
import java.io.BufferedReader;
import java.io.InputStream;
import java.io.InputStreamReader;
import java.lang.ref.WeakReference;
import java.net.HttpURLConnection;
import java.net.URL;
import java.util.ArrayList;
import java.util.List;
import java.util.Map;
import android.os.Handler;
import android.os.Looper;
import android.os.Message;
import java.lang.ref.WeakReference;
public class TsListParser {

    private static final String TAG = "TsListParser";
    private static final double AD_DURATION_THRESHOLD = 30.0; // 广告判定阈值（秒）
    private String m3u8Url;
    private Map<String, String> headers;
    private final Players player;
    private List<AdGroup> adGroups = new ArrayList<>(); // 改为广告组列表（替代原adSegments）

    List<Integer> discontinuityIndices = new ArrayList<>(); // 仅记录分片分隔位置

    // 广告跳过检测的消息标识
    private static final int MSG_AD_SKIP_CHECK = 1;
    // 静态内部类 Handler（避免内存泄漏）
    private static class AdSkipHandler extends Handler {
        private final WeakReference<TsListParser> parserRef;

        public AdSkipHandler(TsListParser parser) {
            super(Looper.getMainLooper()); // 绑定主线程（如需子线程可改为 Looper.myLooper()）
            this.parserRef = new WeakReference<>(parser);
        }

        @Override
        public void handleMessage(Message msg) {
            TsListParser parser = parserRef.get();
            if (parser == null) {
                // 引用已释放，移除所有消息
                removeMessages(MSG_AD_SKIP_CHECK);
                return;
            }
            if (msg.what == MSG_AD_SKIP_CHECK) {
                try {
                    // 执行广告跳过检测
                    parser.handleAdSkipOnTimelineChanged();
                    // 延迟 1 秒发送下一次检测消息（实现每秒执行）
                    sendEmptyMessageDelayed(MSG_AD_SKIP_CHECK, 1000);
                } catch (Exception e) {
                    Logger.e(TAG, "Ad skip check failed", e);
                }
            }
        }
    }

    // 广告跳过检测的 Handler 实例
    private AdSkipHandler adSkipHandler;

    // 仅保留核心构造器
    public TsListParser(Players player) {
        this.player = player;
    }

    public void setVideo(Map<String, String> headers, String url) {
        this.m3u8Url = url;
        this.headers = headers == null ? Map.of() : headers;
        App.execute(() -> {
            this.adGroups = getAdGroups(); // 改为获取广告组
            // 1. 先停止之前的定时任务（防止重复）
            stopAdSkipDetection();

            // 2. 初始化 Handler 并启动每秒检测
            if (adSkipHandler == null) {
                adSkipHandler = new AdSkipHandler(this);
            }
            // 立即发送第一个检测消息，之后每秒循环
            adSkipHandler.sendEmptyMessage(MSG_AD_SKIP_CHECK);
            Logger.d(TAG, "Ad skip check started, interval: 1s");
        });
    }
    /**
     * 停止广告跳过检测（播放器销毁/切换视频时调用）
     */
    public void stopAdSkipDetection() {
        if (adSkipHandler != null) {
            adSkipHandler.removeMessages(MSG_AD_SKIP_CHECK);
            adSkipHandler = null;
            Logger.d(TAG, "Ad skip check stopped");
        }
    }
    /**
     * 核心：解析并提取广告组列表（每个广告组包含起止时间）
     * @return 广告组列表
     */
    public List<AdGroup> getAdGroups() {
        if (player == null || player.isEmpty()) {
            return new ArrayList<>();
        }
        List<TsSegment> allSegments = parseTsList(); // 解析所有分片（不带广告标记）
        return extractAdGroups(allSegments); // 提取广告组
    }

    /**
     * 基础解析：提取所有TS分片（仅保留URL、时长、时间范围，无广告标记）
     */
    private List<TsSegment> parseTsList() {
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

        tsSegments = parseM3u8Content(m3u8Content); // 仅解析分片，不标记广告
        calculateSegmentTimeRange(tsSegments); // 计算分片时间范围
        return tsSegments;
    }

    // 校验是否为m3u8链接
    private boolean isM3u8Url(String url) {
        return url != null && (url.endsWith(".m3u8") || url.contains(".m3u8?") || url.contains("m3u8/"));
    }

    // 主逻辑：获取m3u8内容（兼容主m3u8/子m3u8）
    private String getM3u8Content() {
        // 1. 获取初始m3u8内容（可能是主m3u8）
        String initialContent = getInitialM3u8Content(m3u8Url, headers);
        if (initialContent.isEmpty()) return "";

        // 2. 判断是否是包含EXT-X-STREAM-INF的主m3u8
        if (initialContent.contains("#EXT-X-STREAM-INF")) {
            // 3. 提取子m3u8的相对路径（最后一行非注释行）
            String subM3u8Path = extractSubM3u8Path(initialContent);
            if (!subM3u8Path.isEmpty()) {
                // 4. 拼接子m3u8的完整URL
                String baseUrl = getBaseUrl(m3u8Url,subM3u8Path.startsWith("/"));
                String subM3u8Url = getFullTsUrl(baseUrl, subM3u8Path);
                Logger.d(TAG, "Main m3u8 found, sub m3u8 url: %s", subM3u8Url);
                // 5. 重新请求子m3u8的内容（真正的TS分片列表）
                return getInitialM3u8Content(subM3u8Url, headers);
            }
        }

        // 非主m3u8，直接返回初始内容
        return initialContent;
    }

    /**
     * 提取主m3u8中最后一行的子m3u8路径（非注释、非空行）
     */
    private String extractSubM3u8Path(String m3u8Content) {
        String[] lines = m3u8Content.split("\n");
        // 倒序遍历，找到最后一行有效路径
        for (int i = lines.length - 1; i >= 0; i--) {
            String line = lines[i].trim();
            if (!line.isEmpty() && !line.startsWith("#")) {
                return line;
            }
        }
        Logger.w(TAG, "Extract sub m3u8 path failed, content: %s", m3u8Content);
        return "";
    }

    /**
     * 基础逻辑：获取单个m3u8链接的内容（抽离复用）
     */
    private String getInitialM3u8Content(String urlStr, Map<String, String> headers) {
        HttpURLConnection conn = null;
        InputStream is = null;
        BufferedReader br = null;
        StringBuilder content = new StringBuilder();
        try {
            URL url = new URL(urlStr);
            conn = (HttpURLConnection) url.openConnection();
            // 设置请求头
            for (Map.Entry<String, String> entry : headers.entrySet()) {
                conn.setRequestProperty(entry.getKey(), entry.getValue());
            }
            conn.setConnectTimeout(5000);
            conn.setReadTimeout(10000);
            conn.setRequestMethod("GET");

            if (conn.getResponseCode() != HttpURLConnection.HTTP_OK) {
                Logger.e(TAG, "M3u8 request failed, code: %d, url: %s", conn.getResponseCode(), urlStr);
                return "";
            }

            is = conn.getInputStream();
            br = new BufferedReader(new InputStreamReader(is, "UTF-8"));
            String line;
            while ((line = br.readLine()) != null) {
                content.append(line).append("\n");
            }
        } catch (Exception e) {
            Logger.e(TAG, "Get m3u8 content exception, url: %s", urlStr, e);
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

    // 解析m3u8内容为TS分片列表（仅提取URL、时长，无广告标记）
    private List<TsSegment> parseM3u8Content(String m3u8Content) {
        List<TsSegment> tsSegments = new ArrayList<>();
        discontinuityIndices = new ArrayList<>(); // 仅记录分片分隔位置
        String[] lines = m3u8Content.split("\n");
        String baseUrl = getBaseUrl(m3u8Url,false);
        double currentTsDuration = 0.0;
        boolean hasDiscontinuity = false;

        for (String line : lines) {
            line = line.trim();
            if (line.isEmpty()) continue;

            // 处理分片分隔标记（仅记录位置）
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

            // 创建分片对象（无广告标记）
            TsSegment segment = new TsSegment(tsUrl, currentTsDuration);
            tsSegments.add(segment);

            // 记录分隔符后的分片索引（用于分组）
            if (hasDiscontinuity) {
                discontinuityIndices.add(tsSegments.size() - 1);
                hasDiscontinuity = false;
            }
            currentTsDuration = 0.0;
        }
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

    // 计算分片时间范围（供广告组提取使用）
    private void calculateSegmentTimeRange(List<TsSegment> tsSegments) {
        double currentProgress = 0.0;
        for (TsSegment segment : tsSegments) {
            segment.setStartTime(currentProgress);
            currentProgress += segment.getDuration();
            segment.setEndTime(currentProgress);
        }
    }

    /**
     * 提取广告组列表（按EXT-X-DISCONTINUITY分组，判断每组是否为广告）
     */
    private List<AdGroup> extractAdGroups(List<TsSegment> tsSegments) {
        List<AdGroup> adGroups = new ArrayList<>();
        if (tsSegments.isEmpty()) return adGroups;

        // 2. 构建分组索引（0 → 分隔符1 → 分隔符2 → ... → 最后一个分片）
        List<Integer> groupIndices = new ArrayList<>();
        groupIndices.add(0);
        groupIndices.addAll(discontinuityIndices);
        groupIndices.add(tsSegments.size());

        // 3. 遍历每个分组，判断是否为广告组并计算起止时间
        for (int i = 0; i < groupIndices.size() - 1; i++) {
            int groupStartIdx = groupIndices.get(i);
            int groupEndIdx = groupIndices.get(i + 1);

            // 边界校验
            if (groupStartIdx >= tsSegments.size() || groupEndIdx > tsSegments.size()) {
                continue;
            }

            // 4. 计算分组总时长
            double groupTotalDuration = 0.0;
            for (int j = groupStartIdx; j < groupEndIdx; j++) {
                groupTotalDuration += tsSegments.get(j).getDuration();
            }

            // 5. 短于阈值则标记为广告组（复用TsSegment的时间属性逻辑）
            if (groupTotalDuration > 0 && groupTotalDuration < AD_DURATION_THRESHOLD) {
                double groupStartTime = tsSegments.get(groupStartIdx).getStartTime();
                double groupEndTime = tsSegments.get(groupEndIdx - 1).getEndTime();
                adGroups.add(new AdGroup(groupStartTime, groupEndTime));
                Logger.d(TAG, "Ad group found: start=%.2fs, end=%.2fs, duration=%.2fs",
                        groupStartTime, groupEndTime, groupTotalDuration);
            }
        }

        // 限制广告组数量，避免异常数据
        if (adGroups.size() > 15) {
            adGroups = new ArrayList<>();
        }

        return adGroups;
    }


    // 获取m3u8基础URL
    @NonNull
    // 获取m3u8基础URL（修改后的实现）
    private String getBaseUrl(String m3u8Url,boolean flag) {
        // 空值兜底
        if (m3u8Url == null || m3u8Url.isEmpty()) {
            return "";
        }

        // 1. 定位协议分隔符 "://" 的位置（区分 http/https 协议）
        int protocolEndIdx = m3u8Url.indexOf("://");
        if (protocolEndIdx == -1) {
            // 无协议的URL（如相对路径、本地路径），兼容原逻辑（找最后一个/）
            if (m3u8Url.contains("/")) {
                return m3u8Url.substring(0, m3u8Url.lastIndexOf("/"));
            }
            return m3u8Url;
        }

        // 2. 跳过协议部分（://），找第一个 "/" 的位置
        int firstSlashAfterProtocol = m3u8Url.indexOf("/", protocolEndIdx + 3);
        if (firstSlashAfterProtocol == -1) {
            // 协议后无 "/"（如 https://example.com），直接返回原URL
            return m3u8Url;
        }
        if (flag){
            // 3. 截取到协议后第一个 "/" 为止（不包含该 "/"），得到「协议+域名/」
            return m3u8Url.substring(0, firstSlashAfterProtocol);
        }
        // 3. 最后一个 "/" 为止（包含该 "/"），得到「协议+域名/」
        return m3u8Url.substring(0, m3u8Url.lastIndexOf("/")+1);
    }

    // 拼接TS完整URL（复用逻辑处理子m3u8路径）
    private String getFullTsUrl(String baseUrl, String tsPath) {
        if (tsPath.startsWith("http://") || tsPath.startsWith("https://")) {
            return tsPath;
        }
        return baseUrl + tsPath;
    }

    /**
     * 【核心保留】在播放进度变化时检测并跳过广告（适配广告组逻辑）
     */
    public void handleAdSkipOnTimelineChanged() {
        if (adGroups.isEmpty() || player == null || player.isEmpty()) {
            Logger.w(TAG, "Ad skip: adGroups empty or player null");
            return;
        }

        try {
            double currentProgress = player.get().getCurrentPosition() / 1000.0;
            AdGroup currentAdGroup = findCurrentAdGroup(adGroups, currentProgress);
            if (currentAdGroup != null) {
                long skipToPosition = (long) (currentAdGroup.getEndTime() * 1000);
                if (Math.abs(skipToPosition - player.get().getCurrentPosition()) > 500) {
                    Logger.d(TAG, "Skip ad group: current=%fs, skip to=%fs (ms=%d)",
                            currentProgress, currentAdGroup.getEndTime(), skipToPosition);
                    player.seekTo(skipToPosition);
                }
            }
        } catch (Exception e) {
            Logger.e(TAG, "Ad skip exception", e);
        }
    }

    // 查找当前进度所在的广告组
    private static AdGroup findCurrentAdGroup(List<AdGroup> adGroups, double currentProgress) {
        for (AdGroup adGroup : adGroups) {
            if (currentProgress >= (adGroup.getStartTime()) && currentProgress < adGroup.getEndTime()) {
                return adGroup;
            }
        }
        return null;
    }

    /**
     * 复用TsSegment结构的核心数据模型：TS分片（无广告标记）
     */
    public static class TsSegment {
        private final String url;
        private final double duration;
        private double startTime; // 分片开始时间（秒）
        private double endTime;   // 分片结束时间（秒）

        public TsSegment(String url, double duration) {
            this.url = url;
            this.duration = duration;
            this.startTime = 0.0;
            this.endTime = 0.0;
        }

        // 仅保留必要的Getter/Setter
        public String getUrl() { return url; }
        public double getDuration() { return duration; }
        public double getStartTime() { return startTime; }
        public void setStartTime(double startTime) { this.startTime = startTime; }
        public double getEndTime() { return endTime; }
        public void setEndTime(double endTime) { this.endTime = endTime; }
    }

    /**
     * 广告组模型（复用TsSegment的时间属性逻辑，仅保留起止时间）
     */
    public static class AdGroup {
        private final double startTime; // 广告组开始时间（秒）
        private final double endTime;   // 广告组结束时间（秒）

        public AdGroup(double startTime, double endTime) {
            this.startTime = startTime;
            this.endTime = endTime;
        }

        public double getStartTime() { return startTime; }
        public double getEndTime() { return endTime; }
        // 可选：增加广告组时长计算
        public double getDuration() { return endTime - startTime; }
    }
}
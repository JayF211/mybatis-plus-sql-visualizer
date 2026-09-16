package cn.song.mybatisplus.visualizer.feedback;

import java.io.IOException;

/**
 * HTTP 非 2xx（T-FeedbackErr）：仅用于把服务端响应正文带回上层——
 * {@link FeedbackApi#describeHttpFailure} 优先取正文里的 {@code message}，取不到才回退
 * {@link #getMessage()}（保留 {@code Server returned HTTP response code: 400 for URL: …}
 * 这种通道信息，供排查）。
 */
public class HttpFailure extends IOException {
    private final int status;
    private final String body;

    public HttpFailure(int status, String body) {
        super("Server returned HTTP response code: " + status
                + " for URL (body: " + (body.length() > 200 ? body.substring(0, 200) : body) + ")");
        this.status = status;
        this.body = body;
    }

    public int getStatus() {
        return status;
    }

    public String getBody() {
        return body;
    }
}

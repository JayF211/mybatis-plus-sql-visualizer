package cn.song.mybatisplus.visualizer.feedback;

import com.intellij.util.io.HttpRequests;
import java.net.HttpURLConnection;
import java.net.URLConnection;
import java.util.UUID;

/**
 * 生产 HTTP 实现（HttpRequests，尊重 IDE 代理设置）。
 * <ul>
 *  <li>JSON：{@code HttpRequests.post(url, body)} 二参即请求体（243 平台字节码反编译实证）；</li>
 *  <li>multipart：request + tuner 设 POST 方法与 Content-Type（tuner 在连接建立前回调），
 *      body 字节经 {@code Request.write(byte[])} 写出——write/readString 均 javap 核实存在。</li>
 * </ul>
 */
public final class HttpRequestsSender implements FeedbackApi.HttpSender {

    /** Kotlin object 单例等价。 */
    public static final HttpRequestsSender INSTANCE = new HttpRequestsSender();

    private HttpRequestsSender() {
    }

    private static final int CONNECT_TIMEOUT_MS = 10_000;
    private static final int READ_TIMEOUT_MS = 10_000;

    @Override
    public String postJson(String url, String json) throws Exception {
        return HttpRequests.post(url, json)
                .connectTimeout(CONNECT_TIMEOUT_MS)
                .readTimeout(READ_TIMEOUT_MS)
                .productNameAsUserAgent()
                .tuner((URLConnection connection) ->
                        connection.setRequestProperty("Content-Type", "application/json; charset=UTF-8"))
                .throwStatusCodeException(true)
                .connect(request -> request.readString());
    }

    @Override
    public String postMultipart(String url, String fileName, String mimeType, byte[] content) throws Exception {
        String boundary = "mpsv-feed-" + UUID.randomUUID();
        byte[] body = FeedbackApi.buildMultipartBody(fileName, mimeType, content, boundary);
        return HttpRequests.request(url)
                .connectTimeout(CONNECT_TIMEOUT_MS)
                .readTimeout(READ_TIMEOUT_MS)
                .productNameAsUserAgent()
                .tuner((URLConnection connection) -> {
                    ((HttpURLConnection) connection).setRequestMethod("POST");
                    connection.setDoOutput(true);
                    connection.setRequestProperty("Content-Type", "multipart/form-data; boundary=" + boundary);
                })
                .throwStatusCodeException(true)
                .connect(request -> {
                    request.write(body);
                    return request.readString();
                });
    }
}

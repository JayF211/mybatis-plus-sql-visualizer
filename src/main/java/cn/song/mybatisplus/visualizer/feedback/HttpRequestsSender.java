package cn.song.mybatisplus.visualizer.feedback;

import com.intellij.util.io.HttpRequests;
import java.io.ByteArrayOutputStream;
import java.io.InputStream;
import java.net.HttpURLConnection;
import java.net.URLConnection;
import java.nio.charset.StandardCharsets;
import java.util.UUID;
import java.util.zip.GZIPInputStream;

/**
 * 生产 HTTP 实现（HttpRequests，尊重 IDE 代理设置）。
 * <ul>
 *  <li>JSON：{@code HttpRequests.post(url, body)} 二参即请求体（243 平台字节码反编译实证）；</li>
 *  <li>multipart：request + tuner 设 POST 方法与 Content-Type（tuner 在连接建立前回调），
 *      body 字节经 {@code Request.write(byte[])} 写出——write/readString 均 javap 核实存在。</li>
 * </ul>
 *
 * T-FeedbackErr：两条链路都改用手写 POST + 自读响应（不走 {@code throwStatusCodeException}）。
 * 原因是平台默认异常只带通道消息（{@code Server returned HTTP response code: 400 for URL: …}），
 * 而服务端在 4xx 响应体里写明了具体原因；要拿到它必须自己在错误码分支上把 errorStream 读完，
 * 再把「状态码 + 正文」包成 {@link HttpFailure} 抛出去。
 *
 * T-FeedbackErr 真机第二轮（gzip 根因，字节码级实锤）：平台 {@code RequestBuilderImpl} 构造器
 * {@code iconst_1 → putfield myGzip}（gzip 默认 true），connect 时自动加
 * {@code Accept-Encoding: gzip}；Cloudflare 对该头返回 {@code Content-Encoding: gzip} 的压缩响应。
 * 平台自己的 {@code readString()} 会解压，手写读取若直接读原始字节流，得到的 gzip 二进制经 UTF-8
 * 解码成乱码字符串，Gson lenient 模式即报「Expected BEGIN_OBJECT but was STRING at line 1
 * column 1」——正是真机现场（服务端 FR-0023 完整入库、客户端却解析失败）。对策：tuner 显式
 * 协商 {@code Accept-Encoding: gzip}（与平台默认值一致，语义自洽），{@link #readBody} 按
 * {@code Content-Encoding} 响应头解压；显式化后不再依赖平台的隐式行为。
 */
public final class HttpRequestsSender implements FeedbackApi.HttpSender {

    /** Kotlin object 单例等价。 */
    public static final HttpRequestsSender INSTANCE = new HttpRequestsSender();

    private HttpRequestsSender() {
    }

    private static final int CONNECT_TIMEOUT_MS = 10_000;
    private static final int READ_TIMEOUT_MS = 10_000;

    /** T-PerfSweep2-02：响应体/解压输出上限——需求池 API 实际响应 KB 级，2MB 余量足。 */
    private static final int RESPONSE_MAX_BYTES = 2 * 1024 * 1024;

    @Override
    public String postJson(String url, String json) throws Exception {
        return postJsonWithBody(url, json);
    }

    @Override
    public String postMultipart(String url, String fileName, String mimeType, byte[] content) throws Exception {
        String boundary = "mpsv-feed-" + UUID.randomUUID();
        byte[] body = FeedbackApi.buildMultipartBody(fileName, mimeType, content, boundary);
        return send(url, "multipart/form-data; boundary=" + boundary, body);
    }

    private static String postJsonWithBody(String url, String json) throws Exception {
        return send(url, "application/json; charset=UTF-8", json.getBytes(StandardCharsets.UTF_8));
    }

    private static String send(String url, String contentType, byte[] body) throws Exception {
        Response response = HttpRequests.request(url)
                .connectTimeout(CONNECT_TIMEOUT_MS)
                .readTimeout(READ_TIMEOUT_MS)
                .productNameAsUserAgent()
                .tuner((URLConnection connection) -> {
                    HttpURLConnection http = (HttpURLConnection) connection;
                    http.setRequestMethod("POST");
                    http.setDoOutput(true);
                    http.setRequestProperty("Content-Type", contentType);
                    // 显式协商 gzip（平台 myGzip 默认本就为 true，此处显式化保证协商与解压配套）
                    http.setRequestProperty("Accept-Encoding", "gzip");
                })
                .connect(request -> {
                    request.write(body);
                    HttpURLConnection http = (HttpURLConnection) request.getConnection();
                    int status = http.getResponseCode();
                    return new Response(status, readBody(http, status));
                });
        // 4xx/5xx：把服务端正文一并带出去（用户看到的应是「标题长度需在 5-200 字符之间」，
        // 而不是通道级的 Server returned HTTP response code: 400）——浪费一次外层包装只为可读性
        if (response.status < 200 || response.status > 299) {
            throw new HttpFailure(response.status, response.body);
        }
        return response.body;
    }

    /** 读回的响应：正文参数按 UTF-8 解释（服务端 JSON 恒 UTF-8）。 */
    private static final class Response {
        final int status;
        final String body;

        Response(int status, String body) {
            this.status = status;
            this.body = body;
        }
    }

    /**
     * 错误码分支读 errorStream——平台 {@code readString()} 只认成功流，读错流会抛 NPE。
     * {@code Content-Encoding: gzip} 时先解压（真机 gzip 根因，见类注释）。
     */
    private static String readBody(HttpURLConnection connection, int status) {
        try {
            InputStream stream = (status >= 200 && status <= 299) ? connection.getInputStream() : connection.getErrorStream();
            if (stream == null) {
                return "";
            }
            byte[] raw;
            try (InputStream in = stream) {
                raw = readBounded(in, RESPONSE_MAX_BYTES);
            }
            byte[] bodyBytes = raw;
            String encoding = connection.getContentEncoding();
            if (encoding != null && encoding.equalsIgnoreCase("gzip")) {
                try (GZIPInputStream gz = new GZIPInputStream(new java.io.ByteArrayInputStream(raw))) {
                    bodyBytes = readBounded(gz, RESPONSE_MAX_BYTES);
                } catch (Throwable t) {
                    bodyBytes = raw;
                }
            }
            return new String(bodyBytes, StandardCharsets.UTF_8);
        } catch (Throwable t) {
            return "";
        }
    }

    /**
     * T-PerfSweep2-02：有界读取——响应体与 gzip 解压输出统一封顶 maxBytes。
     * 此前 readBytes() 无上限，10s 超时只是限时不限量：对端（自家站点 + CDN）若返回超大
     * 拦截页或 gzip 炸弹，解压输出可在超时保护之外放大内存。达上限即停（后续字节不再消费），
     * 截断产物为非法 JSON，由既有 nonJsonHint 路径给出可读失败归类。
     */
    static byte[] readBounded(InputStream input, int maxBytes) throws java.io.IOException {
        ByteArrayOutputStream out = new ByteArrayOutputStream(Math.min(maxBytes, 64 * 1024));
        byte[] buf = new byte[16 * 1024];
        int total = 0;
        while (total < maxBytes) {
            int n = input.read(buf, 0, Math.min(buf.length, maxBytes - total));
            if (n < 0) {
                break;
            }
            out.write(buf, 0, n);
            total += n;
        }
        return out.toByteArray();
    }
}

package cn.song.mybatisplus.sqlvis.agent.transport;

import cn.song.mybatisplus.sqlvis.agent.AgentState;

import java.io.ByteArrayOutputStream;
import java.io.DataInputStream;
import java.io.DataOutputStream;
import java.io.EOFException;
import java.io.IOException;
import java.net.Socket;

/**
 * 发送线程主循环（§4.3）：loopback TCP 长度前缀帧（4 字节 len + 1 字节 cmd + JSON body）。
 * 连接后先发 HELLO（pid 标识），事件即时 flush，静默 10s 发 HEARTBEAT（IDE 在线态判定）；
 * 入站 CONFIG（禁用/脱敏开关）由同连接读取线程下发 AgentState。断线退避重连（1s→5s 封顶）。
 */
final class SenderLoop implements Runnable {

    private final int serverPort;

    SenderLoop(int serverPort) {
        this.serverPort = serverPort;
    }

    @Override
    public void run() {
        long backoffMs = 1000;
        while (true) {
            Socket socket = null;
            try {
                socket = new Socket("127.0.0.1", serverPort);
                socket.setSoTimeout(1000); // 读入站 CONFIG 用轮询式短超时
                socket.setTcpNoDelay(true);
                backoffMs = 1000; // 连接成功：退避复位

                final DataOutputStream out = new DataOutputStream(socket.getOutputStream());
                final DataInputStream in = new DataInputStream(socket.getInputStream());
                writeFrame(out, EventBus.CMD_HELLO, "{\"pid\":" + pid() + "}");

                final long idleHeartbeatMs = 10_000L;
                long idleMs = 0;
                final cn.song.mybatisplus.sqlvis.agent.model.SqlEvent[] slot =
                        new cn.song.mybatisplus.sqlvis.agent.model.SqlEvent[1];
                while (true) {
                    // 1) 采集事件尽出（每次最多 64 条防饿死心跳）
                    int burst = 0;
                    while (burst < 64 && EventBus.pollEvent(slot, 0)) {
                        writeFrame(out, EventBus.CMD_EVENT, EventBus.encodeEvent(slot[0]));
                        burst++;
                    }
                    // 2) 入站 CONFIG（非阻塞轮询：超时=无数据）
                    try {
                        final int cmd = in.readUnsignedByte();
                        handleInbound(cmd, readPayload(in, cmd));
                        idleMs = 0;
                    } catch (java.net.SocketTimeoutException ignore) {
                        // 无入站数据
                    }
                    // 3) 心跳
                    if (burst == 0) {
                        final long slept = 200L;
                        Thread.sleep(slept);
                        idleMs += slept;
                        if (idleMs >= idleHeartbeatMs) {
                            writeFrame(out, EventBus.CMD_HEARTBEAT, "{}");
                            idleMs = 0;
                        }
                    } else {
                        idleMs = 0;
                    }
                }
            } catch (Throwable t) {
                closeQuietly(socket);
                try {
                    Thread.sleep(backoffMs);
                } catch (InterruptedException ie) {
                    return;
                }
                backoffMs = Math.min(backoffMs * 2, 5000);
            } finally {
                closeQuietly(socket);
            }
        }
    }

    private void handleInbound(int cmd, String payload) {
        if (cmd != EventBus.CMD_CONFIG || payload == null) {
            return;
        }
        // 极简解析（仅两个布尔键）：{"disabled":true,"desensitize":false}
        if (payload.contains("\"disabled\"")) {
            AgentState.setDisabled(payload.contains("\"disabled\":true")
                    || payload.contains("\"disabled\": true"));
        }
        if (payload.contains("\"desensitize\"")) {
            AgentState.setDesensitize(payload.contains("\"desensitize\":false")
                    || payload.contains("\"desensitize\": false") ? false : true);
        }
    }

    private String readPayload(DataInputStream in, int cmd) throws IOException {
        final int len = in.readInt();
        if (len <= 0 || len > 1 << 20) {
            throw new EOFException("bad frame len " + len);
        }
        final byte[] body = new byte[len];
        in.readFully(body);
        return new String(body, java.nio.charset.StandardCharsets.UTF_8);
    }

    static void writeFrame(DataOutputStream out, int cmd, String payload) throws IOException {
        final byte[] body = payload.getBytes(java.nio.charset.StandardCharsets.UTF_8);
        out.writeInt(body.length);
        out.writeByte(cmd);
        out.write(body);
        out.flush();
    }

    private static long pid() {
        try {
            final String name = java.lang.management.ManagementFactory.getRuntimeMXBean().getName();
            final int at = name.indexOf('@');
            return at < 0 ? 0 : Long.parseLong(name.substring(0, at));
        } catch (Throwable t) {
            return 0;
        }
    }

    private static void closeQuietly(Socket s) {
        if (s != null) {
            try {
                s.close();
            } catch (IOException ignore) {
            }
        }
    }
}

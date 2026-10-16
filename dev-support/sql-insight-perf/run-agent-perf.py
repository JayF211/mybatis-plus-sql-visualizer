"""Serial independent JVM trials. Only local synthetic H2; no GUI, business database or Gradle.
Example: python run-agent-perf.py --java C:/jdk17/bin/java.exe --javac C:/jdk17/bin/javac.exe
 --agent <build/dist/mps-sql-agent.jar> --h2 <h2.jar> --servlet <javax.servlet-api.jar> --output <dir>
Artifacts include exact commands, jar SHA256, process stdout, quality and raw timings. No sleep polling.
"""
import argparse
import hashlib
import json
import os
from pathlib import Path
import queue
import socket
import struct
import subprocess
import threading
import time


def exact(sock, count):
    data = bytearray()
    while len(data) < count:
        chunk = sock.recv(count - len(data))
        if not chunk:
            raise EOFError()
        data.extend(chunk)
    return data


class Receiver:
    def __init__(self):
        self.server = socket.socket()
        self.server.bind(("127.0.0.1", 0))
        self.server.listen(1)
        self.port = self.server.getsockname()[1]
        self.connected = threading.Event()
        self.changed = threading.Condition()
        self.counts = {}
        self.execution_ids = set()
        self.duplicate_sql = 0
        self.request_starts = set()
        self.request_ends = set()
        self.quality = None
        self.quality_ack_count = 0
        self.error = None
        self.sock = None
        threading.Thread(target=self.read, daemon=True).start()

    def read(self):
        try:
            self.sock, _ = self.server.accept()
            self.connected.set()
            while True:
                size = struct.unpack(">i", exact(self.sock, 4))[0]
                if not 0 < size <= 1048576:
                    raise ValueError("invalid frame size")
                cmd = exact(self.sock, 1)[0]
                body = json.loads(exact(self.sock, size))
                with self.changed:
                    self.counts[cmd] = self.counts.get(cmd, 0) + 1
                    if cmd == 1:
                        identity = body.get("executionId")
                        if not identity or identity in self.execution_ids:
                            self.duplicate_sql += 1
                        self.execution_ids.add(identity)
                    if cmd == 7:
                        target = self.request_starts if body.get("kind") == "START" else self.request_ends
                        target.add(body.get("requestId"))
                    if cmd == 6:
                        self.quality = body
                        self.quality_ack_count = self.counts.get(5, 0)
                    self.changed.notify_all()
        except (EOFError, OSError):
            pass
        except Exception as failure:
            self.error = repr(failure)
        finally:
            with self.changed:
                self.changed.notify_all()

    def config(self, tracing, token):
        if not self.connected.wait(15):
            raise TimeoutError("agent connect")
        body = json.dumps(dict(disabled=False, desensitize=True, stackFrames=24,
                               captureState=True, captureStateToken=token,
                               requestTracing=tracing, transactionTracing=False)).encode()
        with self.changed:
            expected_ack = self.counts.get(5, 0) + 1
        self.sock.sendall(struct.pack(">iB", len(body), 3) + body)
        self.wait(lambda: self.quality is not None and self.quality_ack_count >= expected_ack
                  and self.quality.get("captureStateToken") == token, 20)

    def wait(self, predicate, timeout):
        end = time.monotonic() + timeout
        with self.changed:
            while not predicate():
                if self.error:
                    raise RuntimeError(self.error)
                remain = end - time.monotonic()
                if remain <= 0:
                    raise TimeoutError("receiver deadline")
                self.changed.wait(remain)

    def drain(self, timeout=60):
        # Conservation, not gap-free sequence: all generated observations must be written or
        # explicitly lost. A flushed socket still is not an acknowledgement of IDE consumption.
        self.wait(lambda: self.quality is not None and self.quality.get("active") == 0 and
                  self.quality.get("generated", -1) == sum(self.quality.get(k, 0) for k in
                    ("written", "queueDropped", "cleared", "encodingFailed", "transportUncertain", "omitted", "captureFailed")) and
                  self.quality.get("controlGenerated", 0) == sum(self.quality.get(k, 0) for k in
                    ("controlWritten", "controlDropped", "controlCleared", "controlEncodingFailed", "controlTransportUncertain")), timeout)

    def snapshot(self):
        with self.changed:
            return dict(counts=dict(self.counts), quality=dict(self.quality or {}), error=self.error,
                        uniqueSql=len(self.execution_ids), duplicateSql=self.duplicate_sql,
                        requestStarts=len(self.request_starts), requestEnds=len(self.request_ends),
                        unmatchedRequestEnds=len(self.request_ends - self.request_starts))

    def close(self):
        if self.sock:
            self.sock.close()
        self.server.close()


def windows_memory(pid):
    if os.name != "nt":
        return {"rssBytes": None, "privateBytes": None, "reason": "Windows GetProcessMemoryInfo unavailable"}
    import ctypes
    from ctypes import wintypes
    class Counters(ctypes.Structure):
        _fields_ = [("cb", wintypes.DWORD), ("PageFaultCount", wintypes.DWORD)] + [(n, ctypes.c_size_t) for n in
          ("PeakWorkingSetSize", "WorkingSetSize", "QuotaPeakPagedPoolUsage", "QuotaPagedPoolUsage", "QuotaPeakNonPagedPoolUsage", "QuotaNonPagedPoolUsage", "PagefileUsage", "PeakPagefileUsage", "PrivateUsage")]
    kernel = ctypes.WinDLL("kernel32", use_last_error=True)
    kernel.OpenProcess.restype = wintypes.HANDLE
    kernel.OpenProcess.argtypes = [wintypes.DWORD, wintypes.BOOL, wintypes.DWORD]
    kernel.CloseHandle.argtypes = [wintypes.HANDLE]
    psapi = ctypes.WinDLL("psapi", use_last_error=True)
    psapi.GetProcessMemoryInfo.argtypes = [wintypes.HANDLE, ctypes.POINTER(Counters), wintypes.DWORD]
    handle = kernel.OpenProcess(0x1000 | 0x10, False, pid)
    info = Counters(); info.cb = ctypes.sizeof(info)
    try:
        if not handle or not psapi.GetProcessMemoryInfo(handle, ctypes.byref(info), info.cb):
            return {"error": ctypes.get_last_error()}
        return dict(rssBytes=info.WorkingSetSize, peakRssBytes=info.PeakWorkingSetSize, privateBytes=info.PrivateUsage)
    finally:
        if handle: kernel.CloseHandle(handle)


def trial(args, mode, index, classes):
    receiver = Receiver() if mode != "none" else None
    command = [args.java, "-Xms128m", "-Xmx512m", "-Djava.awt.headless=true", "-Dsqlinsight.perf.warmRequests=" + str(args.warm_requests)]
    if receiver:
        command += ["-javaagent:" + args.agent + "=port=" + str(receiver.port)]
    command += ["-cp", os.pathsep.join((str(classes), args.h2, args.servlet)), "perf.sqlinsight.SqlInsightWorkload"]
    proc = subprocess.Popen(command, stdin=subprocess.PIPE, stdout=subprocess.PIPE, stderr=subprocess.STDOUT, text=True, encoding="utf-8", errors="replace", bufsize=1)
    lines, output = queue.Queue(), []
    def read():
        for line in proc.stdout:
            output.append(line.rstrip()); lines.put(line.rstrip())
        lines.put(None)
    threading.Thread(target=read, daemon=True).start()
    def until(prefix):
        end = time.monotonic() + 180
        while True:
            line = lines.get(timeout=max(0.01, end - time.monotonic()))
            if line is None: raise RuntimeError("child exited: " + str(proc.poll()))
            if line.startswith(prefix): return line[len(prefix):]
    def send(value): proc.stdin.write(value + "\n"); proc.stdin.flush()
    result = dict(mode=mode, repeat=index, command=command)
    try:
        until("BOOT_READY")
        if receiver: receiver.config(mode == "tracing", "aa01")
        send("SETUP")
        result["warmBlockNanos"] = json.loads(until("WARM_DATA "))
        until("WARM_READY")
        result["lastWarmBlockRatio"] = result["warmBlockNanos"][-1] / result["warmBlockNanos"][-2]
        result["warmStabilityWithin15Percent"] = 0.85 <= result["lastWarmBlockRatio"] <= 1.15
        if receiver:
            receiver.config(mode == "tracing", "aa01")
            receiver.drain()
            result["before"] = receiver.snapshot()
        result["memoryBefore"] = windows_memory(proc.pid)
        send("MEASURE")
        result.update(json.loads(until("RESULT ")))
        result["memoryAfter"] = windows_memory(proc.pid)
        if receiver:
            receiver.config(mode == "tracing", "aa01")
            try: receiver.drain()
            except TimeoutError: result["drainTimedOut"] = True
            result["after"] = receiver.snapshot()
            before = result["before"]; after = result["after"]
            result["receivedSql"] = after["counts"].get(1, 0) - before["counts"].get(1, 0)
            result["receivedRequestFrames"] = after["counts"].get(7, 0) - before["counts"].get(7, 0)
            result["qualityDelta"] = {k: v - before["quality"].get(k, 0) for k, v in after["quality"].items()
                                      if type(v) is int and type(before["quality"].get(k, 0)) is int}
            losses = ("queueDropped", "cleared", "encodingFailed", "transportUncertain", "omitted", "captureFailed",
                      "controlDropped", "controlCleared", "controlEncodingFailed", "controlTransportUncertain", "controlHookFailed")
            result["captureCoverageComplete"] = result["receivedSql"] == result["sqlCount"] and (
                after["uniqueSql"] - before["uniqueSql"] == result["sqlCount"]) and after["duplicateSql"] == before["duplicateSql"] and (
                mode != "tracing" or (result["receivedRequestFrames"] == result["requests"] * 2
                and after["requestStarts"] - before["requestStarts"] == result["requests"]
                and after["requestEnds"] - before["requestEnds"] == result["requests"]
                and after["unmatchedRequestEnds"] == 0)) and all(result["qualityDelta"].get(k, 0) == 0 for k in losses) and not result.get("drainTimedOut", False)
        result["cpuPercentOneCore"] = result["cpuNanos"] * 100 / result["wallNanos"]
        send("DRAINED")
        result.update(json.loads(until("DRAIN_RESULT ")))
        result["memoryDrained"] = windows_memory(proc.pid)
        result["drainedCpuPercentOneCore"] = result["drainedCpuNanos"] * 100 / result["drainedWallNanos"]
        send("EXIT"); result["exitCode"] = proc.wait(timeout=15)
    except Exception as failure:
        result["experimentError"] = repr(failure)
        if receiver: result["failureReceiverState"] = receiver.snapshot()
    finally:
        if proc.poll() is None:
            proc.kill(); proc.wait()  # Only this newly launched child, never another session process.
        if receiver: receiver.close()
        Path(args.output, "%s-%s.stdout.log" % (index, mode)).write_text("\n".join(output), encoding="utf-8")
    Path(args.output, "%s-%s.json" % (index, mode)).write_text(json.dumps(result, indent=2), encoding="utf-8")
    return result


def main():
    p = argparse.ArgumentParser()
    for name in ("java", "javac", "agent", "h2", "servlet", "output"):
        p.add_argument("--" + name, required=True)
    p.add_argument("--repeats", type=int, default=3)
    p.add_argument("--warm-requests", type=int, default=200)
    p.add_argument("--host-contention", default="UNKNOWN: other IDE/UI activity may contend; not an idle-host certification")
    args = p.parse_args()
    root = Path(__file__).resolve().parents[2]
    Path(args.output).mkdir(parents=True, exist_ok=True)
    classes = Path(args.output, "classes"); classes.mkdir(exist_ok=True)
    source = Path(__file__).with_name("SqlInsightWorkload.java")
    subprocess.run([args.javac, "-encoding", "UTF-8", "-d", str(classes), "-cp", args.servlet, str(source)], check=True)
    metadata = dict(hostContention=args.host_contention, agentHeartbeat="production-default-10000ms", gitSha=subprocess.check_output(["git", "-C", str(root), "rev-parse", "HEAD"], text=True).strip(),
                    java=subprocess.check_output([args.java, "-version"], stderr=subprocess.STDOUT, text=True),
                    jars={name: hashlib.sha256(Path(getattr(args, name)).read_bytes()).hexdigest() for name in ("agent", "h2", "servlet")},
                    limitations=["local synthetic H2 + Servlet dispatch, not HTTP/container latency", "driver read/execute workload; startup excluded", "heap pool peak sum is not simultaneous peak", "RSS is sampled before/after; peakRSS includes startup", "no delivery completeness inferred from latency", "warmup block stability must be inspected; use --warm-requests to rerun a fixed longer warmup"])
    results = []
    for repeat in range(args.repeats):
        modes = ["none", "sql", "tracing"]
        modes = modes[repeat % 3:] + modes[:repeat % 3]
        for mode in modes:
            print("RUN", repeat, mode, flush=True)
            row = trial(args, mode, repeat, classes)
            results.append(row)
            if "experimentError" in row:
                Path(args.output, "summary.json").write_text(json.dumps(dict(metadata=metadata, trials=results,
                    stoppedReason="fixture_or_environment_failure; no performance verdict"), indent=2), encoding="utf-8")
                raise RuntimeError(row["experimentError"])
    import statistics
    comparisons = {}
    for baseline in ("none", "sql"):
        base = [r for r in results if r["mode"] == baseline]
        traced = [r for r in results if r["mode"] == "tracing"]
        median = lambda rows, key: statistics.median(r[key] for r in rows)
        comparisons["tracing_vs_" + baseline] = {
            "sqlP95IncreasePercent": (median(traced, "sqlP95Nanos") / median(base, "sqlP95Nanos") - 1) * 100,
            "wallIncreasePercent": (median(traced, "wallNanos") / median(base, "wallNanos") - 1) * 100,
            "cpuPercentagePointIncrease": median(traced, "cpuPercentOneCore") - median(base, "cpuPercentOneCore"),
            "heapUsedIncreaseBytes": median(traced, "heapUsedBytes") - median(base, "heapUsedBytes"),
            "drainedWallIncreasePercent": (median(traced, "drainedWallNanos") / median(base, "drainedWallNanos") - 1) * 100,
            "drainedCpuPercentagePointIncrease": median(traced, "drainedCpuPercentOneCore") - median(base, "drainedCpuPercentOneCore"),
            "allTracingObservationsReceived": all(r.get("captureCoverageComplete", False) for r in traced),
            "warmStableAllTrials": all(r["warmStabilityWithin15Percent"] for r in base + traced),
            "budgetConclusion": "REQUIRES_REVIEW_OF_LOSS_AND_WARMUP_NOT_AUTOMATIC_PASS",
        }
    Path(args.output, "summary.json").write_text(json.dumps(dict(metadata=metadata, trials=results, comparisons=comparisons), indent=2), encoding="utf-8")
    print("RESULT", str(Path(args.output, "summary.json")), flush=True)

if __name__ == "__main__": main()

"""Three serial REAL target JVM functional captures (8/17/21), not a performance benchmark.
Compiles only the dev-support fixture with --release 8. Never runs Gradle or changes production.
Required: --javac --java8 --java17 --java21 --agent --h2-old --h2-new --servlet --output.
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


class Receiver:
    def __init__(self):
        self.server = socket.socket()
        self.server.bind(("127.0.0.1", 0)); self.server.listen(1)
        self.port = self.server.getsockname()[1]
        self.connected = threading.Event(); self.changed = threading.Condition()
        self.frames = []; self.failure = None; self.sock = None; self.acks = 0; self.state_ack = 0; self.state = None
        threading.Thread(target=self.read, daemon=True).start()

    def exact(self, count):
        output = bytearray()
        while len(output) < count:
            value = self.sock.recv(count - len(output))
            if not value: raise EOFError()
            output.extend(value)
        return output

    def read(self):
        try:
            self.sock, _ = self.server.accept(); self.connected.set()
            while True:
                size = struct.unpack(">i", self.exact(4))[0]
                if not 0 < size <= 1048576: raise ValueError("frame size")
                command = self.exact(1)[0]; body = json.loads(self.exact(size))
                with self.changed:
                    if len(self.frames) >= 2000: raise ValueError("fixture frame budget")
                    self.frames.append(dict(command=command, body=body))
                    if command == 5: self.acks += 1
                    if command == 6: self.state = body; self.state_ack = self.acks
                    self.changed.notify_all()
        except (EOFError, OSError): pass
        except Exception as failure: self.failure = repr(failure)
        finally:
            with self.changed: self.changed.notify_all()

    def wait(self, predicate, seconds=30):
        end = time.monotonic() + seconds
        with self.changed:
            while not predicate():
                if self.failure: raise RuntimeError(self.failure)
                remaining = end - time.monotonic()
                if remaining <= 0: raise TimeoutError("functional receiver deadline")
                self.changed.wait(remaining)

    def config(self, token, traces):
        if not self.connected.wait(15): raise TimeoutError("agent connection")
        with self.changed: expected = self.acks + 1
        data = json.dumps(dict(disabled=False, desensitize=True, stackFrames=24, captureState=True,
            captureStateToken=token, requestTracing=traces, transactionTracing=traces)).encode()
        self.sock.sendall(struct.pack(">iB", len(data), 3) + data)
        self.wait(lambda: self.state is not None and self.state_ack >= expected and self.state.get("captureStateToken") == token)

    def close(self):
        if self.sock: self.sock.close()
        self.server.close()


def require(condition, label):
    if not condition: raise AssertionError(label)


def verify(frames, state, business, family):
    bodies = lambda command: [f["body"] for f in frames if f["command"] == command]
    sql, request, transactions = bodies(1), bodies(7), bodies(8)
    require(business == dict(requests=5, requestSqlCalls=3, committedRows=1), "independent business counters")
    require(len(sql) == 4, "three request SQL calls plus independent row-count query")
    require(len({s.get("executionId") for s in sql}) == 4 and all(s.get("executionId") for s in sql), "unique execution identity")
    require(len({s.get("sequence") for s in sql}) == 4 and all(s.get("threadId") for s in sql), "sequence/thread identity")
    starts = {r["requestId"]: r for r in request if r.get("kind") == "START"}
    ends = {r["requestId"]: r for r in request if r.get("kind") == "END"}
    require(len(request) == 10 and len(starts) == 5 and starts.keys() == ends.keys(), "five paired request scopes")
    require(sorted(e["generatedSql"] for e in ends.values()) == [0, 0, 1, 1, 1], "zero SQL requests and per-request SQL counts")
    require(all(e["completedSql"] == e["generatedSql"] and e["sqlCaptureLost"] == 0 for e in ends.values()), "request terminal accounting")
    require(sum(e["status"] == "ERROR" for e in ends.values()) == 1, "implicit JDBC failure request terminal")
    require(sum(s.get("resultStatus") == "ERROR" for s in sql) == 1, "JDBC error capture")
    linked = [s for s in sql if s.get("requestId")]
    require(len(linked) == 3 and all(s["requestId"] in starts for s in linked), "SQL request linkage without leak to row-count query")
    require(all(s.get("runId") == state["runId"] for s in sql + request + transactions), "single run identity")
    require(all(r.get("captureStateToken") == "b009" for r in request + transactions), "control nonce")
    for kind, outcome in (("COMMIT", "COMMITTED"), ("ROLLBACK", "ROLLED_BACK")):
        events = [t for t in transactions if t.get("kind") == kind]
        require(len(events) == 1 and events[0].get("status") == "SUCCESS" and events[0].get("outcome") == outcome, kind)
        tx = events[0]
        require(tx.get("transactionId") and any(s.get("transactionId") == tx["transactionId"] and s.get("requestId") == tx.get("requestId") for s in linked), kind + " SQL transaction link")
        require(tx.get("connectionId") and tx.get("leaseId"), "observed connection/lease identity")
    require(all(state.get(k) == 0 for k in ("queueDropped", "cleared", "encodingFailed", "transportUncertain", "omitted", "captureFailed",
        "controlDropped", "controlCleared", "controlEncodingFailed", "controlTransportUncertain", "controlHookFailed")), "zero capture loss")
    return dict(targetJdk=family, sqlEvents=len(sql), requests=len(starts), transactionFrames=len(transactions),
        requestCoverage="SERVLET_THREAD_ONLY", transactionCoverage="PROXIED_CONNECTION_ONLY", result="FUNCTIONAL_PASS")


def run(args, family, classes):
    receiver = Receiver(); java = getattr(args, "java" + family)
    h2 = args.h2_old if family == "8" else args.h2_new
    command = [java, "-Xms64m", "-Xmx256m", "-Djava.awt.headless=true", "-javaagent:" + args.agent + "=port=" + str(receiver.port),
        "-cp", os.pathsep.join((str(classes), h2, args.servlet)), "perf.sqlinsight.H09FunctionalWorkload"]
    process = subprocess.Popen(command, stdin=subprocess.PIPE, stdout=subprocess.PIPE, stderr=subprocess.STDOUT,
        text=True, encoding="utf-8", errors="replace", bufsize=1)
    lines = queue.Queue(); output = []
    def read():
        for line in process.stdout: output.append(line.rstrip()); lines.put(line.rstrip())
        lines.put(None)
    threading.Thread(target=read, daemon=True).start()
    def until(prefix):
        deadline = time.monotonic() + 60
        while True:
            line = lines.get(timeout=max(0.01, deadline-time.monotonic()))
            if line is None: raise RuntimeError("child exited before " + prefix)
            if line.startswith(prefix): return line[len(prefix):]
    def send(text): process.stdin.write(text + "\n"); process.stdin.flush()
    record = dict(command=command, targetJdk=family)
    try:
        version = until("BOOT "); record["actualJavaVersion"] = version
        require(version.startswith("1.8.") if family == "8" else version.startswith(family + "."), "actual running JDK")
        receiver.config("a009", False); send("SETUP"); until("SETUP_READY")
        receiver.config("b009", True)
        # Wait for the single setup DDL frame to be fully observed before the functional range.
        receiver.wait(lambda: len([f for f in receiver.frames if f["command"] == 1]) == 1)
        with receiver.changed: boundary = len(receiver.frames)
        send("RUN"); business = json.loads(until("RESULT "))
        receiver.config("b009", True)
        receiver.wait(lambda: receiver.state is not None and receiver.state.get("active") == 0 and
            len([f for f in receiver.frames[boundary:] if f["command"] == 1]) == 4 and
            len([f for f in receiver.frames[boundary:] if f["command"] == 7]) == 10 and
            receiver.state.get("controlGenerated") == receiver.state.get("controlWritten"))
        with receiver.changed:
            captured = list(receiver.frames[boundary:]); quality = dict(receiver.state)
        record.update(verify(captured, quality, business, family)); record["business"] = business; record["quality"] = quality; record["frames"] = captured
        send("EXIT"); record["exitCode"] = process.wait(timeout=15); require(record["exitCode"] == 0, "child exit")
    except Exception as failure:
        record["result"] = "FUNCTIONAL_FAIL"; record["error"] = repr(failure); record["frames"] = list(receiver.frames)
    finally:
        if process.poll() is None: process.kill(); process.wait()  # only this freshly launched fixture
        receiver.close()
        Path(args.output, "jdk" + family + ".stdout.log").write_text("\n".join(output), encoding="utf-8")
        Path(args.output, "jdk" + family + ".json").write_text(json.dumps(record, indent=2), encoding="utf-8")
    return record


def main():
    parser = argparse.ArgumentParser()
    for name in ("javac", "java8", "java17", "java21", "agent", "h2-old", "h2-new", "servlet", "output"):
        parser.add_argument("--" + name, required=True)
    args = parser.parse_args(); out = Path(args.output); out.mkdir(parents=True, exist_ok=True)
    classes = out / "classes"; classes.mkdir(exist_ok=True)
    source = Path(__file__).with_name("H09FunctionalWorkload.java")
    compile_command = [args.javac, "--release", "8", "-encoding", "UTF-8", "-cp", args.servlet, "-d", str(classes), str(source)]
    subprocess.run(compile_command, check=True)
    result = dict(compileCommand=compile_command, jars={k:hashlib.sha256(Path(getattr(args,k)).read_bytes()).hexdigest()
        for k in ("agent","h2_old","h2_new","servlet")}, trials=[], limitations=["javax Servlet only; Jakarta not inferred", "local H2 fixture, no business database", "synthetic local fixture writes only; functional evidence, not performance acceptance"])
    for family in ("8", "17", "21"):
        print("RUN JDK" + family, flush=True); result["trials"].append(run(args, family, classes))
        (out / "summary.json").write_text(json.dumps(result, indent=2), encoding="utf-8")
        if result["trials"][-1]["result"] != "FUNCTIONAL_PASS": raise SystemExit(1)
    print("RESULT " + str(out / "summary.json"), flush=True)

if __name__ == "__main__": main()

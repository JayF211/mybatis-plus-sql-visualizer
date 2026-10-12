// Minimal MCP client over Streamable HTTP for the JetBrains built-in MCP server
const BASE = process.env.MCP_BASE || "http://127.0.0.1:64342";
const ENDPOINT = BASE + "/stream";
const ORIGIN = BASE;

let sessionId = null;
let nextId = 0;

function parseSse(text) {
  let out = null;
  for (const line of text.split("\n")) {
    const m = line.match(/^data:\s?(.*)$/);
    if (m) {
      try { out = JSON.parse(m[1]); } catch { /* keep last good */ }
    }
  }
  return out;
}

async function post(body, { notify = false, timeoutMs = 90000 } = {}) {
  const headers = {
    "Content-Type": "application/json",
    "Accept": "application/json, text/event-stream",
    "Origin": ORIGIN,
  };
  if (sessionId) headers["mcp-session-id"] = sessionId;
  const res = await fetch(ENDPOINT, {
    method: "POST",
    headers,
    body: JSON.stringify(body),
    signal: AbortSignal.timeout(timeoutMs),
  });
  const sid = res.headers.get("mcp-session-id");
  if (sid) sessionId = sid;
  if (notify) return null;
  const ct = res.headers.get("content-type") || "";
  const text = await res.text();
  if (res.status >= 400) throw new Error(`HTTP ${res.status}: ${text.slice(0, 400)}`);
  if (ct.includes("text/event-stream")) {
    const msg = parseSse(text);
    if (!msg) throw new Error("no SSE data frame in response: " + text.slice(0, 300));
    return msg;
  }
  try { return JSON.parse(text); } catch { throw new Error("non-JSON response: " + text.slice(0, 300)); }
}

async function rpc(method, params, timeoutMs) {
  const msg = { jsonrpc: "2.0", id: ++nextId, method, params };
  const res = await post(msg, { timeoutMs });
  return res;
}

async function init() {
  const res = await rpc("initialize", {
    protocolVersion: "2025-03-26",
    capabilities: {},
    clientInfo: { name: "mpsv-mcp-smoketest", version: "1.0.0" },
  }, 30000);
  if (res.error) throw new Error("initialize failed: " + JSON.stringify(res.error));
  await post({ jsonrpc: "2.0", method: "notifications/initialized" }, { notify: true });
  return res.result;
}

async function listTools() {
  const res = await rpc("tools/list", {}, 60000);
  if (res.error) throw new Error("tools/list failed: " + JSON.stringify(res.error));
  return res.result.tools;
}

async function callTool(name, args, timeoutMs = 90000) {
  const res = await rpc("tools/call", { name, arguments: args }, timeoutMs);
  if (res.error) return { toolError: JSON.stringify(res.error) };
  const r = res.result || {};
  const text = (r.content || []).map((c) => c.text || "").join("\n");
  return { isError: !!r.isError, text };
}

function trunc(s, n) {
  s = String(s ?? "");
  return s.length <= n ? s : s.slice(0, n) + ` …[+${s.length - n} chars]`;
}

async function main() {
  const cmd = process.argv[2] || "list";
  const server = await init();
  console.log("SERVER:", server.serverInfo?.name, server.serverInfo?.version, "| proto", server.protocolVersion);

  if (cmd === "list") {
    const tools = await listTools();
    console.log("TOOLS(" + tools.length + "):");
    for (const t of tools) {
      const req = Object.keys(t.inputSchema?.properties || {}).filter(
        (k) => !(t.inputSchema.required || []).includes(k),
      );
      console.log(`- ${t.name} | ${t.title || ""} | req=[${(t.inputSchema?.required || []).join(",")}] opt=[${req.join(",")}]`);
    }
    return;
  }

  if (cmd === "call") {
    const name = process.argv[3];
    let argText = process.argv[4] || "{}";
    if (argText.startsWith("@")) argText = (await import("node:fs")).readFileSync(argText.slice(1), "utf8");
    const args = JSON.parse(argText);
    const timeoutMs = parseInt(process.argv[5] || "90000");
    const r = await callTool(name, args, timeoutMs);
    console.log("TOOL:", name, "| isError:", r.isError, "| err:", r.toolError || "-");
    console.log(trunc(r.text, 3000));
    return;
  }

  if (cmd === "suite") {
    const tools = await listTools();
    const names = new Set(tools.map((t) => t.name));
    const sqlv = [...names].filter((n) => n.startsWith("sqlv_"));
    console.log("SQLV TOOLS VISIBLE:", sqlv.length, "|", sqlv.join(", "));

    // demo 工程根取自环境（2026-10-10 T-LocalEnvOverride）；不再写死本机绝对路径
    const demoRoot = process.env.MPSV_DEMO_ROOT;
    if (!demoRoot) {
      console.error("缺少 MPSV_DEMO_ROOT：请指向 demo 工程根目录（键名见 .local/local.env.example）。例如：");
      console.error("  set -a; . .local/local.env; set +a   # 或 export MPSV_DEMO_ROOT=<path>");
      process.exit(1);
    }
    const demo = `${demoRoot}/mybatis-plus-demo`;
    const PP = { projectPath: demoRoot };
    const withPP = (o) => ({ ...o, ...PP });
    const cases = [
      ["sqlv_render_sql#snippet", "sqlv_render_sql", {
        xmlSnippet: '<mapper namespace="t"><select id="q">select id, username from user where 1=1 <if test="username != null">and username like concat(\'%\', #{username}, \'%\')</if> order by id desc</select></mapper>',
      }],
      ["sqlv_render_sql#file", "sqlv_render_sql", { filePath: `${demo}/src/main/resources/mapper/UserMapper.xml`, line: 33 }],
      ["sqlv_schema_context", "sqlv_schema_context", { tables: "user,role,department" }],
      ["sqlv_explain", "sqlv_explain", { selectSql: "select * from user where status = 1" }],
      ["sqlv_render_logs", "sqlv_render_logs", {
        logText: "==>  Preparing: select * from user where id = ? and status = ?\n==> Parameters: 1(Integer), 1(Integer)",
      }],
      ["sqlv_check_sql", "sqlv_check_sql", { sql: "delete from user" }],
      ["sqlv_rollback_plan", "sqlv_rollback_plan", { sql: "update user set age = 30 where id = 5", primaryKeyColumns: "id" }],
      ["sqlv_perf_advice", "sqlv_perf_advice", { sql: "select * from user where username like '%abc%' or age + 1 = 2 order by username" }],
      ["sqlv_crud_sql#file", "sqlv_crud_sql", { filePath: `${demo}/src/main/java/cn/song/mybatisplusdemo/entity/User.java` }],
      ["sqlv_reverse_sql", "sqlv_reverse_sql", { sql: "select id, username from user where age > 18 order by id desc limit 10" }],
      ["sqlv_mapper_check#file", "sqlv_mapper_check", { filePath: `${demo}/src/main/resources/mapper/UserMapper.xml` }],
    ];
    for (const [label, tool, args] of cases) {
      if (!names.has(tool)) {
        console.log(`\n## ${label} => TOOL NOT VISIBLE (gate off or not registered)`);
        continue;
      }
      try {
        const r = await callTool(tool, withPP(args));
        console.log(`\n## ${label} => isError=${r.isError} ${r.toolError ? "JSONRPC_ERROR:" + trunc(r.toolError, 200) : ""}`);
        console.log(trunc(r.text, 900));
      } catch (e) {
        console.log(`\n## ${label} => TRANSPORT FAIL: ${trunc(e.message, 300)}`);
      }
    }
    return;
  }
}

main().catch((e) => { console.error("FATAL:", e.message); process.exit(1); });

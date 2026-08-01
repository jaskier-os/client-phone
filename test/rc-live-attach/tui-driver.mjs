#!/usr/bin/env node
/**
 * PC-side control server for the live-attach phone e2e.
 *
 * The instrumented tests run on the phone and need to drive the PC half of the
 * feature: start a REAL interactive remote-session TUI on a known
 * sessionId/workDir, type into it, read what it printed, and interrogate the
 * live CLI's own attach state. The phone reaches this server over
 * `adb reverse tcp:8792 tcp:8792`, so no LAN/firewall/VPN path is involved.
 *
 * Nothing here fakes any part of the system under test. The TUI is the real
 * binary in a real pty; the attach socket is the real one the CLI published;
 * the orchestrator, pc-agent and attach handshake are untouched. This process
 * only does what a human at the keyboard would do, plus read-only probes.
 *
 * Endpoints (all JSON):
 *   GET  /health                      -> {ok, pid}
 *   POST /tui/start {workDir, model, permissionMode, sessionId?}
 *                                     -> {sessionId, pid, startTicks}
 *   POST /tui/type {text}             -> {ok}   (types + Enter, like a human)
 *   GET  /tui/screen?since=N          -> {text, len}  ANSI-stripped pty output
 *   GET  /tui/registry                -> the CLI's ~/.claude/sessions/<pid>.json
 *   GET  /tui/probe                   -> hello handshake on the live attach
 *                                        socket: {sessionId, cwd, attached,
 *                                        permissionMode, pid}
 *   POST /tui/attach-attempt          -> issues a real `attach` frame as a
 *                                        second control peer; returns the
 *                                        server's verdict ({code} on refusal)
 *   GET  /procs/headless              -> {count, argvs} of headless CLIs
 *                                        spawned by pc-agent (--sdk-url)
 *   POST /tui/kill {signal}           -> {ok}
 *   POST /reset                       -> kill the TUI, clear buffers
 *
 * Usage:
 *   node tui-driver.mjs [--port 8792]
 */

import fs from 'node:fs';
import http from 'node:http';
import net from 'node:net';
import os from 'node:os';
import path from 'node:path';
import { execFileSync } from 'node:child_process';
import { createRequire } from 'node:module';
import { randomUUID } from 'node:crypto';

const require = createRequire(import.meta.url);
const HERE = path.dirname(new URL(import.meta.url).pathname);
const REPO_AI = path.resolve(HERE, '../../../..');
const CLI_BIN = process.env.REMOTE_SESSION_BIN
  || path.join(REPO_AI, 'remote-session-cli/bin/remote-session');
// node-pty is not a dependency of this test dir; borrow pc-agent's copy, which
// is the same one the production spawn path uses.
const pty = require(path.join(REPO_AI, 'agents/pc/pc-agent/node_modules/node-pty'));

const PORT = Number(argValue('--port') ?? process.env.TUI_DRIVER_PORT ?? 8792);
const ATTACH_PROTOCOL_VERSION = 1;

function argValue(flag) {
  const i = process.argv.indexOf(flag);
  return i >= 0 ? process.argv[i + 1] : undefined;
}

// ---------------------------------------------------------------------------
// TUI process state
// ---------------------------------------------------------------------------

/** @type {{proc: any, sessionId: string, workDir: string, buf: string} | null} */
let tui = null;

// CSI / OSC / two-char escapes. The CSI branch deliberately does NOT accept
// ECMA-48 intermediate bytes (0x20-0x2F): that class includes SPACE, and being
// greedy it swallows the run of spaces that follows a colour reset, turning
// "Quick safety check" into "Quicksafetycheck" -- every screen assertion then
// fails for reasons that look nothing like the real cause. Terminal output from
// Ink never uses those intermediates.
const ANSI = /\u001b\[[0-9;?]*[@-~]|\u001b\][^\u0007]*(\u0007|\u001b\\)|\u001b[@-Z\\-_]/g;

function stripAnsi(s) {
  return s.replace(ANSI, '');
}

function sessionsDir() {
  return path.join(process.env.HOME || os.homedir(), '.claude', 'sessions');
}

/**
 * The runtime dir the CLI must publish its attach socket into, and that
 * pc-agent must independently resolve to the same value -- session-registry
 * rejects any attachSocketPath that is not exactly
 * `$XDG_RUNTIME_DIR/remote-session/attach-<pid>.sock`.
 *
 * Falls back to the systemd-standard /run/user/<uid> so the driver works from a
 * shell that did not inherit XDG_RUNTIME_DIR. Verified to be ours and 0700
 * before use, matching the CLI's own check; if it is not, we leave the variable
 * alone and let the CLI report "attach not published" rather than pointing it
 * at a directory another user can read the secret from.
 */
function runtimeDir() {
  const candidates = [process.env.XDG_RUNTIME_DIR, `/run/user/${process.getuid()}`];
  for (const dir of candidates) {
    if (!dir) continue;
    try {
      const st = fs.statSync(dir);
      if (!st.isDirectory()) continue;
      if (st.uid !== process.getuid()) continue;
      if ((st.mode & 0o077) !== 0) continue;
      return dir;
    } catch { /* try next */ }
  }
  throw new Error(
    'No usable XDG_RUNTIME_DIR (owner-only) found; the CLI cannot publish an ' +
    'attach socket and pc-agent will never find it. Run the driver from a ' +
    'normal desktop session.',
  );
}

/**
 * Flatten a session transcript to plain text (user + assistant turns, in file
 * order) so tests can assert on what the conversation actually contains.
 *
 * Path mirrors the CLI's own layout: ~/.claude/projects/<cwd with / and . as
 * dashes>/<sessionId>.jsonl. Missing file -> '' (the session simply has no
 * turns yet), so callers can poll.
 */
function readTranscriptText(workDir, sessionId) {
  const sanitized = workDir.replace(/[/.]/g, '-');
  const file = path.join(
    process.env.HOME || os.homedir(), '.claude', 'projects', sanitized, `${sessionId}.jsonl`,
  );
  let raw;
  try {
    raw = fs.readFileSync(file, 'utf8');
  } catch {
    return '';
  }
  const out = [];
  for (const line of raw.split('\n')) {
    if (!line.trim()) continue;
    let rec;
    try { rec = JSON.parse(line); } catch { continue; }
    const content = rec?.message?.content;
    if (typeof content === 'string') {
      out.push(content);
    } else if (Array.isArray(content)) {
      for (const block of content) {
        if (block?.type === 'text' && typeof block.text === 'string') out.push(block.text);
      }
    }
  }
  return out.join('\n');
}

function findOnPath(bin) {
  for (const dir of (process.env.PATH || '').split(path.delimiter)) {
    if (!dir) continue;
    const candidate = path.join(dir, bin);
    try { if (fs.existsSync(candidate)) return candidate; } catch { /* ignore */ }
  }
  return null;
}

function readRegistry(pid) {
  try {
    return JSON.parse(fs.readFileSync(path.join(sessionsDir(), `${pid}.json`), 'utf8'));
  } catch {
    return null;
  }
}

/**
 * The CLI shows a blocking workspace-trust dialog for any directory not already
 * marked hasTrustDialogAccepted in ~/.claude.json, and never reaches the REPL
 * (so it never publishes an attach socket). Fail fast and say exactly what to
 * do, rather than letting every test die on an opaque timeout.
 *
 * We deliberately do NOT write the trust flag ourselves: silently marking a
 * directory trusted in the user's real config on their behalf is not something
 * a test driver should do.
 */
function assertTrusted(workDir) {
  const real = fs.realpathSync(workDir);
  let projects = {};
  try {
    projects = JSON.parse(
      fs.readFileSync(path.join(process.env.HOME || os.homedir(), '.claude.json'), 'utf8'),
    ).projects ?? {};
  } catch { /* treated as "nothing trusted" below */ }

  for (const [dir, cfg] of Object.entries(projects)) {
    if (!cfg?.hasTrustDialogAccepted) continue;
    // The CLI trusts a directory if it or any ancestor was accepted.
    if (real === dir || real.startsWith(dir.endsWith('/') ? dir : `${dir}/`)) return;
  }
  throw new Error(
    `workDir ${real} is not a trusted project, so the CLI will block on the ` +
    'workspace-trust dialog and never publish an attach socket. Use a workDir ' +
    'under an already-trusted directory, or open it once interactively and ' +
    'accept the prompt.',
  );
}

function startTui({ workDir, model, permissionMode, sessionId }) {
  if (tui) throw new Error('a TUI is already running; POST /reset first');
  if (!workDir) throw new Error('workDir is required');
  fs.mkdirSync(workDir, { recursive: true });
  assertTrusted(workDir);

  const sid = sessionId || randomUUID();
  const args = ['--session-id', sid];
  // Only pin a model when a test explicitly asks for one. Otherwise let the TUI
  // restore the user's own last-used model, exactly as a hand-started session
  // does -- forcing a default here would silently test a model the user never
  // uses (and, with a 1M-context model, a different billing entitlement).
  if (model) args.push('--model', model);
  // Never bypassPermissions/dontAsk by default: attachServer refuses to attach
  // an auto-approving session, which is the behaviour under test elsewhere.
  args.push('--permission-mode', permissionMode || 'default');

  // Same env hygiene as the production spawn path: a nested-session marker
  // makes the CLI refuse to start.
  const env = { ...process.env, TERM: 'xterm-256color' };
  delete env.CLAUDECODE;
  for (const k of Object.keys(env)) if (k.startsWith('CLAUDE_CODE_')) delete env[k];
  // The attach socket is published into $XDG_RUNTIME_DIR and NOWHERE else --
  // no $HOME fallback. A driver launched from a context without it (a bare
  // ssh session, some service managers) would start a CLI that silently never
  // becomes attachable, and every test would fail with an unhelpful timeout.
  env.XDG_RUNTIME_DIR = runtimeDir();

  // Launch inside a REAL terminal window so the PC user can watch the session
  // the phone attaches to. A bare pty is invisible, which makes "both sides in
  // sync" impossible to observe. Input is delivered via kitty's remote-control
  // socket (writing to kitty's own stdin would NOT reach the child).
  const ktty = findOnPath('kitty');
  const controlSocket = ktty
    ? path.join(runtimeDir(), `rc-tui-${process.pid}-${Date.now()}.sock`)
    : null;

  const [cmd, cmdArgs] = ktty
    ? [ktty, [
        '--title', `remote-session ${sid.slice(0, 8)}`,
        '--listen-on', `unix:${controlSocket}`,
        '-o', 'allow_remote_control=socket-only',
        '-e', CLI_BIN, ...args,
      ]]
    : [CLI_BIN, args];

  const proc = pty.spawn(cmd, cmdArgs, {
    name: 'xterm-256color',
    cols: 120,
    rows: 40,
    cwd: workDir,
    env,
  });

  tui = { proc, sessionId: sid, workDir, buf: '', controlSocket, kitty: ktty };
  proc.onData(chunk => {
    if (!tui) return;
    tui.buf += stripAnsi(String(chunk));
    // Bounded: a long-running TUI would otherwise grow without limit.
    if (tui.buf.length > 2_000_000) tui.buf = tui.buf.slice(-1_000_000);
  });
  proc.onExit(({ exitCode, signal }) => {
    if (tui && tui.proc === proc) {
      tui.exited = { exitCode, signal, at: Date.now() };
    }
  });

  return { sessionId: sid, pid: proc.pid };
}

/**
 * Wait until the CLI has published an attach socket, i.e. it is attachable.
 *
 * Requires an already-trusted workDir (see assertTrusted). An untrusted one
 * blocks on the workspace-trust dialog and never reaches the REPL, so the
 * socket never appears and every test times out. We refuse such a workDir up
 * front with an actionable error instead of driving the dialog by keystroke:
 * scraping the TUI for dialog text is brittle and, when it misfires, the
 * symptom (a bare 60s timeout) looks nothing like the cause.
 */
async function waitAttachable(sessionId, timeoutMs = 60_000) {
  const deadline = Date.now() + timeoutMs;
  for (;;) {
    // Look up by sessionId, NOT by the pid we spawned: inside a terminal
    // wrapper that pid is the EMULATOR's, and the CLI (which is what registers
    // itself) is a child with a different pid.
    const entry = findRegistryBySession(sessionId);
    if (entry?.attachSocketPath && fs.existsSync(entry.attachSocketPath)) return entry;

    if (tui?.exited) {
      throw new Error(`CLI for ${sessionId} exited before publishing an attach socket`);
    }
    if (Date.now() > deadline) {
      throw new Error(
        `CLI for ${sessionId} never published an attach socket within ${timeoutMs}ms.`,
      );
    }
    await sleep(250);
  }
}

/** The live registry entry for a conversation, or null. */
function findRegistryBySession(sessionId) {
  let names;
  try {
    names = fs.readdirSync(sessionsDir());
  } catch {
    return null;
  }
  for (const name of names) {
    if (!name.endsWith('.json')) continue;
    let entry;
    try {
      entry = JSON.parse(fs.readFileSync(path.join(sessionsDir(), name), 'utf8'));
    } catch {
      continue;
    }
    if (entry?.sessionId !== sessionId) continue;
    try { process.kill(entry.pid, 0); } catch { continue; }
    return entry;
  }
  return null;
}

const sleep = ms => new Promise(r => setTimeout(r, ms));

/**
 * Deliver keystrokes to the CLI.
 *
 * When the session runs inside kitty, the pty we own belongs to KITTY, not to
 * the CLI -- writing to it does nothing useful. kitty's remote-control
 * `send-text` injects into the window's child instead, which is exactly what a
 * human typing would produce. Without a terminal wrapper the pty IS the CLI, so
 * write directly.
 */
function writeToTui(t, data) {
  if (!t.controlSocket) {
    t.proc.write(data);
    return;
  }
  execFileSync(t.kitty, [
    '@', '--to', `unix:${t.controlSocket}`, 'send-text', '--', data,
  ], { timeout: 10_000 });
}

// ---------------------------------------------------------------------------
// Attach-socket probes
//
// The CLI is the only authority on whether it considers itself attached, which
// conversation it is on and which tier it is running. Reading that straight
// from the live socket is what makes the phone-side assertions non-vacuous:
// "the phone showed a reply" alone cannot distinguish attach from spawn.
// ---------------------------------------------------------------------------

function connectControl(socketPath) {
  return new Promise((resolve, reject) => {
    const sock = net.connect(socketPath);
    sock.setEncoding('utf8');
    const onErr = err => { sock.destroy(); reject(err); };
    sock.once('error', onErr);
    sock.once('connect', () => { sock.removeListener('error', onErr); resolve(sock); });
  });
}

/**
 * Run a short control-socket exchange and collect frames until `done(frame)`
 * returns truthy or the timeout expires.
 */
async function controlExchange(socketPath, send, done, timeoutMs = 12_000) {
  const sock = await connectControl(socketPath);
  const secret = fs.readFileSync(socketPath.replace(/\.sock$/, '.secret'), 'utf8').trim();
  let buffered = '';
  const frames = [];
  let settle;
  const finished = new Promise(res => { settle = res; });
  const timer = setTimeout(() => settle(null), timeoutMs);

  sock.on('data', chunk => {
    buffered += String(chunk);
    for (;;) {
      const nl = buffered.indexOf('\n');
      if (nl < 0) break;
      const line = buffered.slice(0, nl);
      buffered = buffered.slice(nl + 1);
      if (!line.trim()) continue;
      let frame;
      try { frame = JSON.parse(line); } catch { continue; }
      frames.push(frame);
      if (done(frame, frames)) settle(frame);
    }
  });
  sock.on('close', () => settle(null));

  const write = f => sock.write(JSON.stringify({ v: ATTACH_PROTOCOL_VERSION, ...f }) + '\n');
  write({ type: 'hello', secret });
  if (send) await send(write, frames, () => finished);

  const hit = await finished;
  clearTimeout(timer);
  sock.destroy();
  return { hit, frames };
}

/** hello only: read the CLI's authoritative view of itself. */
async function probe(socketPath) {
  const { hit, frames } = await controlExchange(
    socketPath,
    null,
    f => f.type === 'hello_ok',
  );
  if (!hit) throw new Error(`no hello_ok from ${socketPath}; frames=${JSON.stringify(frames)}`);
  return hit;
}

/**
 * Issue a real `attach` frame as a SECOND control peer. Used to prove the
 * double-attach guard: while pc-agent holds the attachment this must come back
 * `already_attached`, never a second orchestrator WebSocket.
 *
 * The refusal happens before beginAttach, so the placeholder wsUrl/token are
 * never dialled. If the server ever regressed to opening the socket first this
 * request would fail loudly instead of silently passing.
 */
async function attachAttempt(socketPath, { sessionId, workDir }) {
  const { hit, frames } = await controlExchange(
    socketPath,
    async write => {
      await sleep(150);
      write({
        type: 'attach',
        attachId: randomUUID(),
        sessionId,
        workDir,
        wsUrl: 'wss://127.0.0.1:1/ws/remote-control?session=driver-probe',
        authToken: 'driver-probe-not-a-real-token',
        permissionMode: 'default',
      });
    },
    f => f.type === 'attach_error' || f.type === 'attach_ok' || f.type === 'detached',
  );
  return { verdict: hit, frames };
}

// ---------------------------------------------------------------------------
// Headless-spawn census
//
// pc-agent's spawn path is the only thing on this machine that runs the CLI
// with `--sdk-url`. Counting those is how "attach, do not spawn" is proven:
// the count must not move across the phone opening the conversation.
// ---------------------------------------------------------------------------

function headlessProcs() {
  let out = '';
  try {
    out = execFileSync('pgrep', ['-af', '--', '--sdk-url'], { encoding: 'utf8' });
  } catch {
    out = ''; // pgrep exits 1 when nothing matches
  }
  const argvs = out.split('\n').map(l => l.trim()).filter(Boolean)
    // Never count ourselves.
    .filter(l => !l.includes('tui-driver.mjs'));
  return { count: argvs.length, argvs };
}

// ---------------------------------------------------------------------------
// HTTP
// ---------------------------------------------------------------------------

function readBody(req) {
  return new Promise((resolve, reject) => {
    let data = '';
    req.on('data', c => {
      data += c;
      if (data.length > 1_000_000) reject(new Error('body too large'));
    });
    req.on('end', () => {
      if (!data) return resolve({});
      try { resolve(JSON.parse(data)); } catch (e) { reject(e); }
    });
    req.on('error', reject);
  });
}

function requireTui() {
  if (!tui) throw new Error('no TUI running');
  return tui;
}

async function requireSocket() {
  const t = requireTui();
  const entry = await waitAttachable(t.sessionId, 30_000);
  return entry.attachSocketPath;
}

const routes = {
  // runtimeDir is reported so a mismatch with pc-agent's own XDG_RUNTIME_DIR
  // (which would make every attach silently fall back to spawn) is visible
  // before a test spends minutes failing.
  'GET /health': async () => ({
    ok: true, pid: process.pid, cliBin: CLI_BIN, runtimeDir: runtimeDir(),
  }),

  'POST /tui/start': async body => {
    const started = startTui(body);
    const entry = await waitAttachable(started.sessionId, Number(body.timeoutMs) || 90_000);
    return {
      ...started,
      // The CLI's pid, not the terminal emulator's: this is the pid callers
      // assert against (probe.pid) and the one pc-agent attaches to.
      pid: entry.pid,
      startTicks: entry.startTicks ?? null,
      attachSocketPath: entry.attachSocketPath,
      cwd: entry.cwd,
      kind: entry.kind,
    };
  },

  'POST /tui/type': async body => {
    const t = requireTui();
    const text = String(body.text ?? '');
    if (!text) throw new Error('text is required');
    writeToTui(t, text);
    // A human presses Enter as a separate event; the REPL's paste heuristics
    // treat a newline glued to the payload as a multi-line paste instead.
    await sleep(Number(body.enterDelayMs) || 200);
    writeToTui(t, '\r');
    return { ok: true };
  },

  'POST /tui/keys': async body => {
    const t = requireTui();
    writeToTui(t, String(body.raw ?? ''));
    return { ok: true };
  },

  // Conversation content, read from the session's own transcript rather than
  // scraped off the terminal. Inside a terminal wrapper the pty carries the
  // EMULATOR's output, not the CLI's, so screen-scraping would see nothing --
  // and even bare-pty scraping was brittle (ANSI/reflow). The transcript is
  // what the CLI actually committed, which is the stronger assertion anyway.
  'GET /tui/screen': async (_body, url) => {
    const t = requireTui();
    const since = Number(url.searchParams.get('since') || 0);
    const text = readTranscriptText(t.workDir, t.sessionId);
    return { text: text.slice(since), len: text.length, exited: t.exited ?? null };
  },

  // Registry entry for ANY live session by conversation id -- including ones
  // pc-agent started, which this driver does not own and therefore cannot look
  // up via its own `tui` handle.
  'GET /session': async (_body, url) => {
    const sessionId = url.searchParams.get('sessionId');
    if (!sessionId) throw new Error('sessionId is required');
    const entry = findRegistryBySession(sessionId);
    return entry
      ? {
          found: true,
          pid: entry.pid,
          kind: entry.kind ?? '',
          cwd: entry.cwd ?? '',
          attachSocketPath: entry.attachSocketPath ?? '',
        }
      : { found: false };
  },

  'GET /tui/registry': async () => {
    const t = requireTui();
    return { entry: readRegistry(t.proc.pid), pid: t.proc.pid };
  },

  'GET /tui/probe': async () => probe(await requireSocket()),

  'POST /tui/attach-attempt': async () => {
    const t = requireTui();
    const socketPath = await requireSocket();
    const { verdict, frames } = await attachAttempt(socketPath, {
      sessionId: t.sessionId,
      workDir: t.workDir,
    });
    return {
      type: verdict?.type ?? null,
      code: verdict?.code ?? null,
      message: verdict?.message ?? null,
      frames,
    };
  },

  'GET /procs/headless': async () => headlessProcs(),

  'POST /tui/kill': async body => {
    const t = requireTui();
    const signal = body.signal || 'SIGKILL';
    try { process.kill(t.proc.pid, signal); } catch (e) { return { ok: false, error: e.message }; }
    return { ok: true, pid: t.proc.pid, signal };
  },

  'POST /reset': async () => {
    if (tui) {
      try { process.kill(tui.proc.pid, 'SIGKILL'); } catch { /* already gone */ }
      // The CLI unlinks its registry file on exit; give it a moment so the
      // next findLiveSession does not see a corpse.
      await sleep(500);
      tui = null;
    }
    return { ok: true };
  },
};

const server = http.createServer(async (req, res) => {
  const url = new URL(req.url, 'http://127.0.0.1');
  const key = `${req.method} ${url.pathname}`;
  const handler = routes[key];
  res.setHeader('Content-Type', 'application/json');
  if (!handler) {
    res.statusCode = 404;
    res.end(JSON.stringify({ error: `no route ${key}` }));
    return;
  }
  try {
    const body = req.method === 'POST' ? await readBody(req) : {};
    const out = await handler(body, url);
    res.statusCode = 200;
    res.end(JSON.stringify(out ?? { ok: true }));
  } catch (err) {
    res.statusCode = 500;
    res.end(JSON.stringify({ error: err.message }));
  }
});

// Loopback only. adb reverse gives the phone access; nothing else needs it,
// and this server can type into a live agent with shell access.
server.listen(PORT, '127.0.0.1', () => {
  console.log(`[tui-driver] listening on 127.0.0.1:${PORT} (cli=${CLI_BIN})`);
});

for (const sig of ['SIGINT', 'SIGTERM']) {
  process.on(sig, () => {
    if (tui) { try { process.kill(tui.proc.pid, 'SIGKILL'); } catch { /* gone */ } }
    server.close(() => process.exit(0));
  });
}

/**
 * Driver self-check: proves the PC control server can bring a live TUI up to
 * "attachable" and answer probes, without any phone involvement.
 *
 * Run the driver first:  node tui-driver.mjs &
 * Then:                  node selfcheck.mjs
 */
import http from 'node:http';

const PORT = Number(process.env.TUI_DRIVER_PORT || 8792);
const WORK_DIR = process.env.WORK_DIR || '/home/varingait/.cache/rc-live-attach-test';

function req(method, path, body) {
  return new Promise((resolve, reject) => {
    const data = body ? JSON.stringify(body) : null;
    const r = http.request(
      {
        host: '127.0.0.1',
        port: PORT,
        path,
        method,
        timeout: 180_000,
        headers: data
          ? { 'Content-Type': 'application/json', 'Content-Length': Buffer.byteLength(data) }
          : {},
      },
      res => {
        let b = '';
        res.on('data', c => (b += c));
        res.on('end', () => resolve({ code: res.statusCode, body: b }));
      },
    );
    r.on('error', reject);
    r.on('timeout', () => r.destroy(new Error('timeout')));
    if (data) r.write(data);
    r.end();
  });
}

const t0 = Date.now();
const health = await req('GET', '/health');
console.log('health:', health.code, health.body.slice(0, 200));

const start = await req('POST', '/tui/start', {
  workDir: WORK_DIR,
  model: 'claude-sonnet-4-6',
  permissionMode: 'default',
});
console.log(`start (${((Date.now() - t0) / 1000).toFixed(1)}s):`, start.code, start.body.slice(0, 400));
if (start.code !== 200) process.exit(1);

const probe = await req('GET', '/tui/probe');
console.log('probe:', probe.code, probe.body.slice(0, 400));

const procs = await req('GET', '/procs/headless');
console.log('headless census:', procs.code, procs.body.slice(0, 200));

await req('POST', '/reset', {});
console.log('reset ok');

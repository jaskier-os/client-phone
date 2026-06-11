#!/usr/bin/env node
/**
 * Scrapes Yandex MapKit Android documentation into .txt files.
 *
 * Connects to an existing Playwright MCP browser via CDP to reuse its
 * session cookies. Uses in-page fetch() for scraping -- same-origin
 * requests bypass Yandex CAPTCHA/bot detection.
 *
 * Usage:
 *   node scrape-yandex-mapkit.mjs [--out <dir>] [--batch-size <n>] [--dry-run] [--resume] [--cdp-port <port>]
 */

import { chromium } from "playwright";
import { writeFile, mkdir, access } from "node:fs/promises";
import path from "node:path";
import { parseArgs } from "node:util";

const { values: args } = parseArgs({
  options: {
    out: { type: "string", default: path.resolve(import.meta.dirname, "../docs/yandex-mapkit") },
    "batch-size": { type: "string", default: "10" },
    "dry-run": { type: "boolean", default: false },
    resume: { type: "boolean", default: false },
    "cdp-port": { type: "string", default: "0" },
  },
});

const OUT_DIR = path.resolve(args.out);
const BATCH_SIZE = Number(args["batch-size"]);
const DRY_RUN = args["dry-run"];
const RESUME = args.resume;
const BASE = "https://yandex.ru/maps-api/docs/mapkit/";
const START_PAGE = BASE + "android/generated/getting_started.html";

// ---------------------------------------------------------------------------
// Helpers
// ---------------------------------------------------------------------------

function urlToFilename(relativeUrl) {
  let name = relativeUrl
    .replace(/\.html$/, "")
    .replace(/#.*$/, "")
    .replace(/\//g, "__");
  if (!name || name === ".") name = "index";
  return name + ".txt";
}

async function fileExists(fp) {
  try { await access(fp); return true; } catch { return false; }
}

// ---------------------------------------------------------------------------
// Find CDP port from running Playwright MCP Chrome process
// ---------------------------------------------------------------------------

async function findCdpPort() {
  const explicit = Number(args["cdp-port"]);
  if (explicit > 0) return explicit;

  const { execSync } = await import("node:child_process");
  try {
    const ps = execSync(
      "ps aux | grep 'remote-debugging-port' | grep 'mcp-chrome' | grep -v grep",
      { encoding: "utf-8" }
    );
    const match = ps.match(/--remote-debugging-port=(\d+)/);
    if (match) return Number(match[1]);
  } catch { /* fallback */ }

  throw new Error(
    "Could not find Playwright MCP Chrome process. " +
    "Pass --cdp-port explicitly or ensure the MCP browser is running."
  );
}

// ---------------------------------------------------------------------------
// Connect to existing browser
// ---------------------------------------------------------------------------

async function connectBrowser(port) {
  console.log(`Connecting to CDP on port ${port}...`);

  // Get the WebSocket URL from the /json/version endpoint
  const resp = await fetch(`http://127.0.0.1:${port}/json/version`);
  const info = await resp.json();
  const wsUrl = info.webSocketDebuggerUrl;
  if (!wsUrl) throw new Error("No webSocketDebuggerUrl found in /json/version");

  console.log(`WebSocket URL: ${wsUrl}`);
  const browser = await chromium.connectOverCDP(wsUrl);
  const contexts = browser.contexts();
  if (contexts.length === 0) {
    throw new Error("No browser contexts found. Is the MCP browser active?");
  }
  const ctx = contexts[0];
  console.log(`Connected. ${ctx.pages().length} page(s) in context.`);
  return { browser, ctx };
}

// ---------------------------------------------------------------------------
// Phase 1 - Collect all doc links
// ---------------------------------------------------------------------------

async function collectLinks(page) {
  console.log("[1/3] Navigating to docs root...");
  await page.goto(START_PAGE, { waitUntil: "domcontentloaded", timeout: 30_000 });
  await page.waitForTimeout(3000);

  // Check for CAPTCHA on initial load
  const isCaptcha = await page.evaluate(() =>
    document.title.includes("Подтвердите") ||
    document.body.innerText.includes("похожи на автоматические")
  );
  if (isCaptcha) {
    throw new Error("CAPTCHA on initial page load. The browser session may be invalid.");
  }

  // Expand all sidebar dropdowns
  console.log("[1/3] Expanding sidebar dropdowns...");
  let expanded = 0;
  for (let round = 0; round < 10; round++) {
    const buttons = await page.$$('button[aria-expanded="false"]');
    if (buttons.length === 0) break;
    for (const btn of buttons) {
      try {
        await btn.scrollIntoViewIfNeeded();
        await btn.click();
        expanded++;
        await page.waitForTimeout(100 + Math.random() * 150);
      } catch { /* detached */ }
    }
    await page.waitForTimeout(400);
  }
  console.log(`[1/3] Expanded ${expanded} dropdown(s).`);

  const links = await page.evaluate(() => {
    const seen = new Set();
    const results = [];
    for (const a of document.querySelectorAll("a[href]")) {
      let resolved = a.href;
      if (!resolved) continue;
      const raw = a.getAttribute("href");
      if (!raw || raw.startsWith("#") || raw.startsWith("javascript")) continue;
      resolved = resolved.split("#")[0];
      if (!resolved.includes("/maps-api/docs/mapkit/")) continue;
      const rel = resolved.split("/maps-api/docs/mapkit/")[1] || "";
      if (rel.startsWith("Swift/") || rel.startsWith("Objective-C/") ||
          rel.startsWith("ios/") || rel.startsWith("flutter/") ||
          rel.startsWith("dart/")) continue;
      if (seen.has(resolved)) continue;
      seen.add(resolved);
      results.push({ text: a.textContent.trim().substring(0, 120), url: resolved });
    }
    return results;
  });

  console.log(`[1/3] Collected ${links.length} unique doc links.`);
  return links;
}

// ---------------------------------------------------------------------------
// Phase 2 - Batch fetch using in-page fetch()
// ---------------------------------------------------------------------------

async function fetchBatch(page, urls) {
  return page.evaluate(async (urls) => {
    function extractText(el) {
      const lines = [];
      for (const child of el.children) {
        const tag = child.tagName;
        const text = child.textContent?.trim();
        if (!text) continue;
        if (tag === "H1") { lines.push("# " + text, ""); }
        else if (tag === "H2") { lines.push("## " + text, ""); }
        else if (tag === "H3") { lines.push("### " + text, ""); }
        else if (tag === "H4") { lines.push("#### " + text, ""); }
        else if (tag === "PRE" || child.querySelector("pre")) {
          const codeEl = child.querySelector("code") || child.querySelector("pre") || child;
          lines.push("```", codeEl.textContent.trim(), "```", "");
        } else if (tag === "TABLE") {
          for (const row of child.querySelectorAll("tr")) {
            const cells = Array.from(row.querySelectorAll("th, td"))
              .map(c => c.textContent.trim().replace(/\s+/g, " "));
            lines.push("| " + cells.join(" | ") + " |");
          }
          lines.push("");
        } else if (tag === "UL" || tag === "OL") {
          for (const li of child.querySelectorAll(":scope > li"))
            lines.push("- " + li.textContent.trim().replace(/\s+/g, " "));
          lines.push("");
        } else {
          if (child.children.length > 0 && child.querySelector("h1,h2,h3,h4,pre,table,ul,ol"))
            lines.push(...extractText(child));
          else lines.push(text.replace(/\s+/g, " "), "");
        }
      }
      return lines;
    }

    const results = [];
    for (const url of urls) {
      try {
        const resp = await fetch(url);
        const html = await resp.text();
        const doc = new DOMParser().parseFromString(html, "text/html");

        if (doc.title.includes("Подтвердите") || html.includes("похожи на автоматические")) {
          results.push({ url, captcha: true });
          continue;
        }

        const main = doc.querySelector("main") || doc.body;
        const title = doc.querySelector("h1")?.textContent?.trim() || doc.title;
        const content = extractText(main).join("\n");
        results.push({ url, title, content });
      } catch (err) {
        results.push({ url, error: err.message || String(err) });
      }

      // Small jitter between in-page fetches (200-600ms)
      await new Promise(r => setTimeout(r, 200 + Math.random() * 400));
    }
    return results;
  }, urls);
}

// ---------------------------------------------------------------------------
// Main
// ---------------------------------------------------------------------------

async function main() {
  console.log(`Output dir  : ${OUT_DIR}`);
  console.log(`Batch size  : ${BATCH_SIZE}`);
  console.log(`Dry run     : ${DRY_RUN}`);
  console.log(`Resume      : ${RESUME}`);
  console.log();

  await mkdir(OUT_DIR, { recursive: true });

  const cdpPort = await findCdpPort();
  const { browser, ctx } = await connectBrowser(cdpPort);

  // Open a new page for our work
  const page = await ctx.newPage();

  try {
    // Phase 1: collect links
    const links = await collectLinks(page);

    if (links.length === 0) {
      console.error("No links found!");
      return;
    }

    // Save manifest
    await writeFile(
      path.join(OUT_DIR, "_manifest.txt"),
      links.map(l => `${l.text}\t${l.url}`).join("\n"),
      "utf-8"
    );
    console.log(`[1/3] Manifest saved (${links.length} links).`);

    if (DRY_RUN) {
      console.log("\nDry run -- links:");
      for (const l of links) console.log(`  ${l.text} -> ${l.url}`);
      return;
    }

    // Filter for resume
    let toScrape = [];
    let skipped = 0;
    for (const link of links) {
      const rel = link.url.split("/maps-api/docs/mapkit/")[1] || "index";
      const filename = urlToFilename(rel);
      if (RESUME && await fileExists(path.join(OUT_DIR, filename))) {
        skipped++;
      } else {
        toScrape.push(link);
      }
    }
    if (skipped > 0) console.log(`[2/3] Resuming: skipping ${skipped} existing files.`);

    // Phase 2: batch scrape
    const total = toScrape.length;
    let done = 0;
    let captchaCount = 0;
    const errors = [];

    const batches = [];
    for (let i = 0; i < toScrape.length; i += BATCH_SIZE) {
      batches.push(toScrape.slice(i, i + BATCH_SIZE));
    }

    console.log(`[2/3] Scraping ${total} pages in ${batches.length} batches of ~${BATCH_SIZE}...`);

    for (let bi = 0; bi < batches.length; bi++) {
      const batch = batches[bi];
      const batchUrls = batch.map(l => l.url);

      let results;
      try {
        results = await fetchBatch(page, batchUrls);
      } catch (err) {
        console.error(`[2/3] Batch ${bi + 1} failed: ${err.message}`);
        for (const link of batch) {
          errors.push({ url: link.url, error: `Batch error: ${err.message}` });
          done++;
        }
        continue;
      }

      for (const r of results) {
        done++;
        const rel = r.url.split("/maps-api/docs/mapkit/")[1] || "index";
        const filename = urlToFilename(rel);

        if (r.captcha) {
          captchaCount++;
          errors.push({ url: r.url, error: "CAPTCHA" });
          console.warn(`[2/3] [${done}/${total}] CAPTCHA: ${filename}`);
          continue;
        }
        if (r.error) {
          errors.push({ url: r.url, error: r.error });
          console.error(`[2/3] [${done}/${total}] ERROR: ${filename}: ${r.error}`);
          continue;
        }

        const header = `Title: ${r.title}\nSource: ${r.url}\n${"=".repeat(72)}\n\n`;
        await writeFile(path.join(OUT_DIR, filename), header + r.content, "utf-8");
        console.log(`[2/3] [${done}/${total}] ${filename}`);
      }

      // Inter-batch jitter (1.5-4s)
      if (bi < batches.length - 1) {
        const delay = 1500 + Math.random() * 2500;
        if ((bi + 1) % 10 === 0) {
          console.log(`[2/3] Progress: batch ${bi + 1}/${batches.length} (${done}/${total} pages)`);
        }
        await new Promise(r => setTimeout(r, delay));
      }

      // CAPTCHA backoff
      if (captchaCount > 0 && captchaCount % 5 === 0) {
        console.warn(`[2/3] ${captchaCount} CAPTCHAs, backing off 30s...`);
        await new Promise(r => setTimeout(r, 30_000));
      }
    }

    // Report
    console.log();
    const success = total - errors.length;
    console.log(`[3/3] Done! ${success}/${total} pages saved to ${OUT_DIR}`);
    if (errors.length > 0) {
      console.log(`[3/3] ${errors.length} errors (${captchaCount} CAPTCHAs):`);
      for (const e of errors) console.log(`  ${e.url}: ${e.error}`);
      await writeFile(
        path.join(OUT_DIR, "_errors.txt"),
        errors.map(e => `${e.url}\t${e.error}`).join("\n"),
        "utf-8"
      );
    }
  } finally {
    await page.close();
    if (typeof browser.disconnect === "function") browser.disconnect();
    else if (typeof browser.close === "function") await browser.close();
  }
}

main().catch((err) => {
  console.error("Fatal:", err);
  process.exit(1);
});

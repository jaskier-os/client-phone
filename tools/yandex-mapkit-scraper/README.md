# yandex-mapkit-scraper

Dev tool that scrapes the Yandex MapKit Android documentation into `.txt` files (used
as reference when working on the phone app's MapKit integration). Not part of the app
build.

Connects to an already-running Playwright MCP browser over CDP to reuse its session
cookies, then scrapes via in-page `fetch()` (same-origin requests bypass Yandex's
CAPTCHA/bot detection).

```bash
npm install
node scrape-yandex-mapkit.mjs --out ./yandex-mapkit-docs
# flags: --batch-size <n> --dry-run --resume --cdp-port <port>
```

Requires a running Playwright MCP browser to attach to (CDP port).

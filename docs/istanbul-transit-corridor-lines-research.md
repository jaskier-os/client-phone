# Istanbul "All Lines Serving a Corridor" — Data Source Research

Goal: supplement Google Routes API v2 (TRANSIT), which returns ONE line per
transit step, with ALL co-serving lines on the same board->alight corridor
(e.g. show "34, 34G, 34AS, 34C, 34BZ" instead of just "34G").

## 1. Best data source: IBB Open Data Portal — IETT GTFS (static)

- Landing page: https://data.ibb.gov.tr/en/dataset/iett-gtfs-verisi
- CKAN package API: https://data.ibb.gov.tr/api/3/action/package_show?id=iett-gtfs-verisi
- Package UUID: `8540e256-6df5-4719-85bc-e64e91508ede`
- Direct file downloads (CSV, semicolon-delimited, BOM, Turkish text in UTF-8):
  - routes      https://data.ibb.gov.tr/dataset/8540e256-6df5-4719-85bc-e64e91508ede/resource/46dbe388-c8c2-45c4-ac72-c06953de56a2/download/routes.csv
  - trips       https://data.ibb.gov.tr/dataset/8540e256-6df5-4719-85bc-e64e91508ede/resource/7ff49bdd-b0d2-4a6e-9392-b598f77f5070/download/trips.csv
  - stops       https://data.ibb.gov.tr/dataset/8540e256-6df5-4719-85bc-e64e91508ede/resource/2299bc82-983b-4bdf-8520-5cef8c555e29/download/stops.csv
  - stop_times  https://data.ibb.gov.tr/dataset/8540e256-6df5-4719-85bc-e64e91508ede/resource/23778613-16fe-4d30-b8b8-8ca934ed2978/download/stop_times.csv
  - stop_times.zip (150MB extracted) https://data.ibb.gov.tr/dataset/8540e256-6df5-4719-85bc-e64e91508ede/resource/80401c1c-c240-4a32-8f40-ef697100a681/download/stop_times.zip
  - calendar, agency similar.
- License: Istanbul Metropolitan Municipality Open Data License (https://data.ibb.gov.tr/license) — free, attribution.
- Auth: NONE for downloads (verified HTTP 200). Login only needed to browse logged-in features.
- Freshness: portal text says "will not be updated" but this is FALSE — HTTP
  Last-Modified and CKAN last_modified on routes/trips/stops/stop_times all show
  2026-03-17 (under 3 months old at time of research). agency.csv is older (2024).
- Coverage: IETT buses. ~9,280 routes, ~15,391 stops, ~135,626 trips. Metro/tram/
  Marmaray/ferries are SEPARATE datasets on the same portal (tags: Marmaray, metro,
  sehir hatlari, IDO, Dentur Avrasya) — add them for rail/ferry corridors.

### Data caveats (verified)
- Delimiter is `;` not `,`. UTF-8 with BOM on header.
- stop_lat/stop_lon are MANGLED: dots used as thousands separators and the decimal
  point dropped, e.g. `410.191.700.005.564` = 41.0191700005564. Parse by stripping
  all dots then inserting decimal after the first 2 digits (lat ~41.x, lon ~28-29.x).
- stop_times.csv is 26MB / stop_times.zip is 150MB extracted — load into a DB, do
  not parse per-request.

## 2. Google stop -> GTFS stop_id mapping
Google gives stop name + lat/lng per leg. Stop NAMES will not match IETT exactly
(transliteration, abbreviations), so use NEAREST-NEIGHBOR on lat/lng within ~50-150m.
Then the "lines serving this stop" query is:
  stop_times (stop_id) -> trips (trip_id -> route_id) -> routes (route_id ->
  route_short_name); DISTINCT route_short_name.
For a corridor, take the UNION of lines over all GTFS stops within radius of the
board point (multiple physical platforms share a name/corridor).

## 3. Third-party APIs (checked)
- Transitland v2 REST (https://transit.land/api/v2/rest/...): requires an API key
  (apikey query param / free tier via signup). Unauthenticated requests return
  HTTP 401 (verified). It DOES support "routes serving a stop" via
  /stops?lat=&lon=&radius= then the stop's route list. Viable fallback but adds a
  key + rate limits + dependency.
- Navitia (navitia.io): free dev token; Istanbul coverage is partial/not guaranteed
  vs the official IETT feed — official GTFS is more complete for buses.
- Moovit/Here Transit/OTP public instances: no reliable free "all lines per stop"
  for Istanbul; Moovit has no open public API.
- IETT SOAP services (api.ibb.gov.tr/iett/...asmx?wsdl): returned HTTP 500 on the
  classic endpoints tried — unreliable/changed; do NOT depend on them. GTFS is the
  stable path.

## 4. Recommended architecture: SERVER-SIDE GTFS (option a)
Host the IETT (+ metro/tram/ferry) GTFS on our Node.js backend in SQLite/Postgres.
Reasons: no per-request third-party key/rate limit, no coverage gaps, full control
of the lat/lng fuzzy match, fast indexed lookups, refresh monthly via cron from the
CKAN download URLs. Client-side third-party calls (option b) add latency, a key, and
weaker coverage.

### Loader sketch (monthly cron)
1. Download routes/trips/stops/stop_times CSVs from the URLs above.
2. Normalize: split on `;`, strip BOM, fix lat/lon mangling.
3. Build a precomputed table `stop_lines(stop_id, lat, lon, route_short_names[])`
   by joining stop_times->trips->routes once at load time (avoids per-request joins).
4. Spatial index stops by lat/lon (R-tree / Postgres GiST / simple grid).

### Endpoint
GET /transit/lines?boardLat=&boardLng=&alightLat=&alightLng=&chosen=34G&radius=120
Returns: { "lines": ["34","34G","34AS","34C","34BZ"], "source":"IETT GTFS 2026-03-17" }
Logic: union of route_short_names over all stops within `radius` of (boardLat,boardLng).
Optionally intersect with lines that ALSO pass an alight-radius stop to keep only
corridor-true lines (filters out lines that share the board stop but diverge).

### GoogleRoutingEngine integration
For each transit leg the engine already has board+alight stop lat/lng from the
Routes API response (transitDetails.stopDetails.{departure,arrival}Stop.location).
After parsing the route, call our /transit/lines per leg with those lat/lngs plus
the chosen line short name. Replace the single-line label with the returned union
(put the Google-chosen line first). The join/lookup lives entirely on the backend;
the Android client only renders the returned list.

## 5. Feasibility — user claim CONFIRMED
Ran the join against the live feed. Top multi-line stops:
- ÇARŞI (stop 1765): 27 distinct lines (131K/131V/131Y, 132/132A/132C/132D/132E...).
- ÇAMLIK: 21 lines. PENDIK/SANCAK KÖPRÜSÜ cluster: 19 lines.
- 756 stops serve >=5 lines; 153 stops serve >=10 lines.
The line-family clustering (131x, 132x) is exactly the "34, 34G, 34AS" pattern. So
yes, a single major stop-pair routinely has 5+ buses, and GTFS exposes them all —
precisely the data Google Routes hides.

Note: exact Fatih/Taksim CENTER points landed on minor 1-line stops because that
corridor is largely metro/tram/funicular (separate datasets) and the busy bus
arteries (e.g. Vatan Cd) sit a few hundred metres off center. Use a wider radius
(120-200m) and include the metro/tram/Marmaray GTFS datasets for that corridor.

## Blockers / risks
- Portal mislabels freshness ("will not be updated") but data is current; monitor
  Last-Modified and alert if it goes stale.
- lat/lon mangling MUST be handled or every nearest-neighbor match fails.
- Metro/tram/Marmaray/ferry are separate datasets — load them too for full coverage.
- IETT SOAP API is unreliable (HTTP 500); do not use.
- Transitland is a fallback only (needs key + rate limits).

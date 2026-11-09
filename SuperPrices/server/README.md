# SuperPrices server

A backend for the SuperPrices app. Each day it downloads the price, promotion and store files
that Israeli supermarket chains must publish under the 2014 Food Act, and keeps:

- **A mirror of the files**, so the app can sync a store in seconds instead of waiting on the
  chain's portal (Shufersal's often takes a minute per request, or returns 502).
- **A database** of current prices, promotions and price history across all mirrored stores.
- **A JSON API** for search, product comparison, shopping-list totals and promotions.
- **A web page** at `/` for searching from a browser (Hebrew).

Chains (19): Shufersal, Carrefour (formerly Yeinot Bitan), Rami Levy, Yohananof, Osher Ad,
Tiv Taam, Dor Alon, Keshet Taamim, Fresh Market, Stop Market, Politzer, Salach Dabach,
King Store, Maayan 2000, Zol VeBegadol, Shuk HaIr, Hazi Hinam, Super-Pharm and Wolt.
It uses only the Python standard library (3.11+), so there's nothing to install.

| Portal | Chains | Notes |
|---|---|---|
| url.publishedprices.co.il | Rami Levy, Yohananof, Osher Ad, Tiv Taam, Dor Alon, Keshet, Fresh Market, Stop Market, Politzer, Salach Dabach | Logs in with each chain's public username (empty password) |
| binaprojects.com | King Store, Maayan 2000, Zol VeBegadol, Shuk HaIr | JSON list of the newest 1,000 files |
| Own sites | Shufersal, Carrefour, Hazi Hinam, Super-Pharm, Wolt | Shufersal's is slow (~50 s a page) |

Some Android versions (Android 14, for one) lack the shared portal's root certificate (SSL.com TLS RSA Root CA 2022);
the app ships it in `res/raw` and trusts it for that domain only.

## Running

```sh
cd server
python -m superprices                                # every store of every chain (slow: Shufersal alone takes over an hour)
python -m superprices --stores carrefour/2,shufersal/1   # only some stores (much faster)
python -m superprices --stores carrefour:all         # one whole chain
python -m superprices --sync-once                    # one sync, then exit (for cron)
```

It syncs at start-up, then daily at 06:00 Israel time (the chains publish full files around
04:00–05:00). Data goes to `./data` (`prices.db` plus `files/`).

| Option | Environment | Default | |
|---|---|---|---|
| `--port` | `PORT` / `SP_PORT` | 8080 | |
| `--data` | `SP_DATA` | `data` | database and mirrored files |
| `--stores` | `SP_STORES` | `all` | see above |
| `--sync-hour` | `SP_SYNC_HOUR` | 6 | daily sync, Israel time |
| `--workers` | `SP_WORKERS` | 4 | parallel downloads |
| `--no-sync` | | | serve existing data only |
| | `SP_ADMIN_TOKEN` | | allows `POST /api/sync` from other machines |

A full sync of every store takes a while: Shufersal's listing is 22 pages of about 55 seconds
each per file type, then ~560 stores × 2 files. Expect about 1 GB of disk space.

### Docker

```sh
docker build -t superprices-server server
docker run -d -p 8080:8080 -v superprices-data:/data superprices-server
```

## Using it from the app

In the app: **Stores** tab → **price source** → enter the server address (e.g.
`http://192.168.1.10:8080` on your home network). The app then downloads files from the
server's mirror, and goes to the chain directly for stores the server doesn't have. Price
alerts and history still work on the phone as before.

## API

All responses are JSON. Store keys look like `carrefour/2`, and `stores=` takes a
comma-separated list (leave it out for all stores).

| Request | Returns |
|---|---|
| `GET /api/status` | counts, last sync, progress |
| `GET /api/chains` | chains and store counts |
| `GET /api/stores?chain=&city=&q=` | stores |
| `GET /api/search?q=&stores=&limit=` | products by name or barcode, with min/max price |
| `GET /api/products/{barcode}?stores=` | price in each store, active promotions, price history |
| `POST /api/basket` `{"items":[{"code":"…","qty":2}],"stores":["carrefour/2"]}` | total per store, cheapest split |
| `GET /api/promos?q=&stores=&limit=` | active promotions |
| `GET /api/promos/{chain}/{store}/{id}` | one promotion with its items |
| `GET /api/files/{chain}/{store}` | mirrored files for a store (`_chain` = store list) |
| `GET /files/{chain}/{store}/{kind}` | the file itself, as the chain published it |
| `POST /api/sync` | starts a sync (from localhost, or with `Authorization: Bearer $SP_ADMIN_TOKEN`) |

## Tests

```sh
cd server
python -m unittest discover -s tests
```

They use real files the chains published (shared with the app's tests in
`app/src/test/resources`), and start the HTTP server on a random port.

## Not included yet

- **Victory, H. Cohen, Mahsani Ashuk** publish on laibcatalog.co.il, an ASP.NET form that only
  returns files after a series of postbacks, and its folders can't be listed.
- **Super Yuda, Meshnat Yosef**: their sites didn't respond.
- Chains whose old usernames no longer log in to the shared portal (Cofix, Good Pharm, Yellow and
  others) probably moved to other portals.

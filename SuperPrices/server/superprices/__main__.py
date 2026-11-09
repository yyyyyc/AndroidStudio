"""python -m superprices [--port 8080] [--data ./data] [--stores all] [--sync-hour 6] [--no-sync]"""
from __future__ import annotations

import argparse
import logging
import os

from .api import Api, serve
from .collector import Collector, Scope
from .db import Db


def main() -> None:
    env = os.environ.get
    p = argparse.ArgumentParser(prog="superprices", description=__doc__)
    p.add_argument("--host", default=env("SP_HOST", "0.0.0.0"))
    p.add_argument("--port", type=int, default=int(env("PORT", env("SP_PORT", "8080"))))
    p.add_argument("--data", default=env("SP_DATA", "data"), help="folder for the database and mirrored files")
    p.add_argument("--stores", default=env("SP_STORES", "all"),
                   help='what to mirror: "all", "carrefour:all", or "carrefour/2,shufersal/1"')
    p.add_argument("--sync-hour", type=int, default=int(env("SP_SYNC_HOUR", "6")), help="daily sync hour, Israel time")
    p.add_argument("--workers", type=int, default=int(env("SP_WORKERS", "4")), help="parallel downloads")
    p.add_argument("--no-sync", action="store_true", help="serve existing data only")
    p.add_argument("--sync-once", action="store_true", help="run one sync and exit")
    args = p.parse_args()

    logging.basicConfig(level=env("SP_LOG", "INFO"), format="%(asctime)s %(levelname)s %(name)s: %(message)s")
    os.makedirs(args.data, exist_ok=True)
    db = Db(os.path.join(args.data, "prices.db"))
    collector = Collector(db, args.data, Scope(args.stores), args.workers)

    if args.sync_once:
        print(collector.run())
        return
    if not args.no_sync:
        collector.schedule_daily(args.sync_hour)

    server = serve(Api(db, collector, env("SP_ADMIN_TOKEN")), args.host, args.port)
    logging.info("listening on http://%s:%d", args.host, args.port)
    try:
        server.serve_forever()
    except KeyboardInterrupt:
        pass


if __name__ == "__main__":
    main()

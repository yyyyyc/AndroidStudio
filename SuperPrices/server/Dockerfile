# SuperPrices backend. Standard library only, so there's nothing to pip install.
FROM python:3.13-slim

ENV PYTHONUNBUFFERED=1 \
    SP_DATA=/data \
    SP_STORES=all \
    SP_SYNC_HOUR=6 \
    PORT=8080

WORKDIR /app
COPY superprices ./superprices

RUN useradd --system --uid 10001 app && mkdir -p /data && chown app /data
USER app
VOLUME ["/data"]
EXPOSE 8080

HEALTHCHECK --interval=60s --timeout=5s CMD python -c "import urllib.request,os; urllib.request.urlopen(f'http://127.0.0.1:{os.environ[\"PORT\"]}/api/chains', timeout=4)"

CMD ["python", "-m", "superprices"]

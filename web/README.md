# Fortress AI — Web App (`web/`)

A self-contained web version of the Fortress AI Options Command Center — the same
four screens as the Android `:trader` app (Dashboard · Portfolio · Risk · Settings)
with the dark Fortress theme. No build step, no dependencies: just `index.html`
+ `app.js`.

## Run locally

Open `web/index.html` in a browser, or serve the folder:

```bash
cd web && python3 -m http.server 8000   # then visit http://localhost:8000
```

## Deploy

Pushed to GitHub Pages automatically by `.github/workflows/deploy-web.yml` on every
change to `web/`. Live URL: **https://antoniotate0007.github.io/fv2/**

> First deploy only: if the workflow errors because Pages isn't enabled, set
> **Settings → Pages → Source = GitHub Actions** once, then re-run it.

## Going live with real data

`app.js` holds a `DATA` object with sample positions/values. Replace it with a
`fetch()` against the FastAPI backend (`server/main.py`, the `/v1/*` routes) to
show live portfolio, risk, and earnings data.

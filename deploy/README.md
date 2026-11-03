# Deploying the demo

Two compose files live here:

- `docker-compose.yml` — local/dev. Every service publishes its port on `localhost`; plain HTTP.
- `docker-compose.prod.yml` — a public demo on a VM. Only Caddy publishes 80/443; everything else
  (postgres, decision-service, ai-service, frontend) is reachable only on the compose network.
  Caddy terminates HTTPS with an automatic Let's Encrypt certificate for `DEMO_DOMAIN` and reverse
  proxies to the frontend's nginx, which still does the internal `/api/...` routing and the AI
  rate limit (`nginx/nginx.conf.template`).

## Fresh VM steps

Any Linux VM with Docker + the Compose plugin installed, 4 vCPU / 8 GB RAM, with ports 80 and 443
open to the internet.

1. Point a DNS A record for your chosen domain at the VM's public IP. Caddy only requests a
   certificate for a real, resolvable hostname — it will not issue one for an IP address or
   `localhost`.
2. Clone the repo and `cd` into it.
3. `cp deploy/.env.example deploy/.env` and fill in:
   - `POSTGRES_PASSWORD` — any value; just needs to match between services, which the compose file
     handles for you.
   - `DEMO_DOMAIN` — the hostname from step 1.
   - `ANTHROPIC_API_KEY` — required for the "Ask AI" feature to answer anything. Everything else in
     the demo (Rules, Audit, evaluate/simulate) works without one.
   - `RESET_DEMO_ON_START` — leave at `true` for a public demo so it always comes back clean after
     a restart; set `false` on a VM where you want manually authored drafts/approvals to survive a
     restart.
4. `docker compose -f deploy/docker-compose.prod.yml --env-file deploy/.env up -d --build`
5. `https://<DEMO_DOMAIN>` should be serving the app within a minute or two (first boot also builds
   three images and downloads the local embedding model, so give it a little longer than a plain
   restart).

## Updating

```
git pull
docker compose -f deploy/docker-compose.prod.yml --env-file deploy/.env up -d --build
```

## Nightly reset (optional)

`RESET_DEMO_ON_START=true` only reseeds on container restart, not on a timer. To force a nightly
reset on an always-up VM, add a cron job that restarts the two app services (this re-triggers each
service's `SeedRunner`/`DocumentSeedRunner`, without rebuilding images or touching Caddy's TLS
state):

```cron
0 3 * * * cd /path/to/dmn-rules-demo && docker compose -f deploy/docker-compose.prod.yml restart decision-service ai-service
```

## Logs / troubleshooting

```
docker compose -f deploy/docker-compose.prod.yml logs -f caddy
docker compose -f deploy/docker-compose.prod.yml logs -f decision-service ai-service
```

A `502`/`503` from Caddy right after `up` usually just means the Spring Boot services are still
starting (Flyway migrate + seed + model registry load); `depends_on: service_started` doesn't wait
for the app to be ready, only for the container to start.

## Google Cloud Run

`deploy/gcp/deploy.sh` deploys decision-service, ai-service and the nginx frontend as three Cloud
Run services, backed by a private-IP Cloud SQL Postgres 16 (reached via Direct VPC egress) with
secrets in Secret Manager.

```
export GCP_PROJECT=my-project ANTHROPIC_API_KEY=sk-ant-...   # key is only read on the first run
gcloud auth login && deploy/gcp/deploy.sh
```

Notes: the services run with `RESET_DEMO_ON_START=false` (a reset on every cold start would wipe the
database; an empty database is still seeded once), `max-instances=1`, and are unauthenticated, as the
frontend proxies to them over their public URLs. Re-running the script ships new code.

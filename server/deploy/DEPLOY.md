# Deployment Guide — LLM API Web Server

This guide covers deploying the LLM API web interface (`app.api-divonr.xyz`) to the
24/7 server using systemd and the existing Cloudflare tunnel.

## Architecture

```
Browser  -->  HTTPS  -->  Cloudflare tunnel (app.api-divonr.xyz)  -->  localhost:8091
                                                                       |
                                                        Ktor server (:server module)
                                                        serving React SPA + REST API
                                                               |
                                                        DataRepository (~/.llm-api-web)
```

---

## Step 1: Set secrets in the env file

```bash
cd /home/divonr/ApI/android-app

# Copy the example env file
cp server/deploy/llm-web.env.example server/deploy/llm-web.env

# Edit and fill in real values
nano server/deploy/llm-web.env
```

Required fields to set:
- `WEB_UI_PASSWORD` — the password for the web UI login page
- `WEB_UI_SESSION_SECRET` — a long random secret for signing session cookies

Generate a random secret:
```bash
openssl rand -hex 32
```

---

## Step 2: Build the frontend and server

From the repo root:

```bash
# Build React frontend → web/dist
npm --prefix web run build

# Build server installDist artifact
./gradlew :server:installDist
```

The resulting start script is at:
```
server/build/install/server/bin/server
```

---

## Step 3: Install and enable the systemd service

```bash
# Copy the service file to systemd
sudo cp /home/divonr/ApI/android-app/server/deploy/llm-web.service /etc/systemd/system/llm-web.service

# Reload systemd and enable + start the service
sudo systemctl daemon-reload
sudo systemctl enable --now llm-web

# Check status
sudo systemctl status llm-web
```

To view logs:
```bash
journalctl -u llm-web -f
```

To restart after updating the code:
```bash
# Rebuild (step 2 above), then:
sudo systemctl restart llm-web
```

---

## Step 4: Add the Cloudflare tunnel route

In the Cloudflare Zero Trust dashboard (where you added `sync.api-divonr.xyz`):

1. Go to **Tunnels** → select your tunnel → **Public Hostnames**
2. Add a new route:
   - **Subdomain:** `app`
   - **Domain:** `api-divonr.xyz`
   - **Service:** `HTTP` → `localhost:8091`
3. Save.

Verify: `curl https://app.api-divonr.xyz/health` should return `{"status":"ok"}`.

---

## Step 5: GitHub OAuth App configuration

If you want GitHub integration (connecting your GitHub account to sync files):

1. Go to **GitHub → Settings → Developer settings → OAuth Apps**
2. Find the existing "ApI" OAuth app (used by desktop/Android)
3. Add a new callback URL: `https://app.api-divonr.xyz/oauth/github/callback`
4. Save

The `GITHUB_OAUTH_CLIENT_ID` and `GITHUB_OAUTH_CLIENT_SECRET` in `llm-web.env`
default to the desktop app's credentials — no change needed unless you prefer a
separate web OAuth app.

---

## Step 6: Google OAuth configuration

If you want Google Workspace integration:

1. Go to **Google Cloud Console → APIs & Services → Credentials**
2. Create a new **OAuth 2.0 Client ID**:
   - Application type: **Web application**
   - Name: "ApI Web"
   - Authorized redirect URIs: `https://app.api-divonr.xyz/oauth/google/callback`
3. Copy the **Client ID** and **Client Secret**
4. Add to `server/deploy/llm-web.env`:
   ```
   GOOGLE_OAUTH_CLIENT_ID=your-client-id.apps.googleusercontent.com
   GOOGLE_OAUTH_CLIENT_SECRET=your-client-secret
   ```
5. Restart the service: `sudo systemctl restart llm-web`

---

## Step 7: Off-box smoke test

From any machine (or your phone):

```bash
# Health check
curl https://app.api-divonr.xyz/health
# Expected: {"status":"ok"}

# Open in browser
open https://app.api-divonr.xyz
# Should show the login page
```

Log in with the password you set in `WEB_UI_PASSWORD`.

---

## Updating after code changes

```bash
# 1. Pull latest code
git pull

# 2. Rebuild (if server code changed)
./gradlew :server:installDist

# 3. Rebuild frontend (if web/ changed)
npm --prefix web run build

# 4. Restart service
sudo systemctl restart llm-web
```

Or use the convenience script for a full rebuild + run (dev use only):
```bash
./server/deploy/build-and-run.sh
```

---

## Data location

Server data is stored in `~/.llm-api-web/` by default.
Override with `LLM_WEB_DATA_DIR=/path/to/dir` in `llm-web.env`.

This is separate from the phone/desktop data — the web server maintains
its own independent chat history, settings, and API keys.

---

## Remote sync (share chats with phone/desktop)

The web server can pull chat history from the same sync server used by your
phone/desktop app.  Once enabled, the web UI shows the same chats as your
other devices without any manual import/export.

### How it works

On startup the server:
1. Seeds `RemoteSyncSettings` into `~/.llm-api-web` from the env vars below.
2. Calls `startSync()` to bring the engine online.
3. Does an immediate `pullNow()` so existing chats appear right away.
4. Runs a background loop that calls `pullNow()` every `SYNC_PULL_INTERVAL_SECONDS`
   (default 20 s) so changes made on phone/desktop show up automatically.

Pushes happen automatically whenever the server writes a file (same hook as
desktop/Android).  The sync server is the source of truth.

### Configuration

Add these lines to `server/deploy/llm-web.env` (already gitignored):

```env
# Enable remote sync
SYNC_ENABLED=true

# URL of the sync server — use localhost if it runs on the same box
SYNC_SERVER_URL=http://localhost:8090

# Bearer token from the sync server's env (KEEP SECRET — env file only)
SYNC_TOKEN=<the-bearer-token-from-sync-server-env>

# Username whose data to load (matches SYNC_USER on phone/desktop)
SYNC_USER=default

# Optional: pull interval in seconds (default 20)
# SYNC_PULL_INTERVAL_SECONDS=20
```

> **Security note:** `SYNC_TOKEN` is a secret.  It must only ever live in
> `llm-web.env` (which is gitignored) and in the sync server's env file.
> The token is never logged, never sent to clients, and never stored in any
> response body — the server keeps it server-side only.

### Apply changes

After editing `llm-web.env`:

```bash
sudo systemctl restart llm-web
journalctl -u llm-web -f
```

Look for log lines like:
```
Remote sync configured: url=http://localhost:8090, user=default, interval=20s
Remote sync engine started — triggering initial pull...
Periodic sync pull loop started (interval=20s)
```

### Sync API endpoints (optional / debugging)

Two authenticated endpoints are available for debugging:

| Method | Path | Description |
|--------|------|-------------|
| `GET`  | `/api/sync/status` | Returns `{enabled, serverBaseUrl, lastChangeTick}`. Token omitted. |
| `POST` | `/api/sync/pull`   | Triggers an immediate pull. Returns `{ok:true}`. |

Both require a valid session cookie (same auth as the rest of the API).

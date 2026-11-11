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
                                                  UserRegistry (per-user DataRepository)
                                                  Data: ~/.llm-api-web/users/{username}/
```

Login is **Google Sign-In only** — no password.  Each Google account gets its own
per-user data directory bootstrapped on first login.

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
- `WEB_UI_SESSION_SECRET` — a long random secret for signing session cookies
- `GOOGLE_OAUTH_CLIENT_ID` — Google OAuth client ID (see Step 5 below)
- `GOOGLE_OAUTH_CLIENT_SECRET` — Google OAuth client secret

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

## Step 5: Google Cloud Console — Sign-In OAuth client

This client is used for both **user login** (`/auth/google/callback`) and
(optionally) **Google Workspace integration** (`/oauth/google/callback`).

1. Go to **Google Cloud Console → APIs & Services → Credentials**
2. Create a new **OAuth 2.0 Client ID**:
   - Application type: **Web application**
   - Name: "ApI Web"
   - Authorized redirect URIs (add both):
     - `https://app.api-divonr.xyz/auth/google/callback`  ← user login
     - `https://app.api-divonr.xyz/oauth/google/callback` ← Workspace integration
3. Copy the **Client ID** and **Client Secret**
4. Add to `server/deploy/llm-web.env`:
   ```
   GOOGLE_OAUTH_CLIENT_ID=your-client-id.apps.googleusercontent.com
   GOOGLE_OAUTH_CLIENT_SECRET=your-client-secret
   ```
5. Optionally restrict logins to specific accounts:
   ```
   ALLOWED_GOOGLE_EMAILS=alice@example.com,bob@example.com
   ```
6. Restart the service: `sudo systemctl restart llm-web`

---

## Step 6: GitHub OAuth App configuration (optional)

If you want GitHub integration (connecting your GitHub account to sync files):

1. Go to **GitHub → Settings → Developer settings → OAuth Apps**
2. Find the existing "ApI" OAuth app (used by desktop/Android)
3. Add a new callback URL: `https://app.api-divonr.xyz/oauth/github/callback`
4. Save

The `GITHUB_OAUTH_CLIENT_ID` and `GITHUB_OAUTH_CLIENT_SECRET` in `llm-web.env`
default to the desktop app's credentials — no change needed unless you prefer a
separate web OAuth app.

---

## Step 7: Off-box smoke test

From any machine (or your phone):

```bash
# Health check
curl https://app.api-divonr.xyz/health
# Expected: {"status":"ok"}

# Open in browser
open https://app.api-divonr.xyz
# Should show the login page with "Sign in with Google"
```

Click **Sign in with Google**, complete the OAuth flow, and verify you land on the
app home page.  `GET /api/me` should return your username and email.

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

Each logged-in Google account gets its own directory:
```
~/.llm-api-web/
  users/
    alice_example_com/   ← Alice's chat history, settings, API keys
    bob_example_com/     ← Bob's data
```

---

## Remote sync (per-user, automatic)

Sync is configured automatically at login: the server exchanges the user's
Google ID token with the sync server (`POST /auth/google` on the sync server)
to mint a per-user bearer token, then seeds that token into the user's
`AppSettings.remoteSync` block.  No manual token configuration is needed.

Set `SYNC_SERVER_URL` to point at your sync server (defaults to
`http://localhost:8090`).  Set `SYNC_PULL_INTERVAL_SECONDS` to control how
often background pulls run (default 20 s).

### Sync API endpoints (debugging)

Both require a valid session cookie.

| Method | Path | Description |
|--------|------|-------------|
| `GET`  | `/api/sync/status` | Returns `{enabled, serverBaseUrl, lastChangeTick, reachable}`. Token omitted. |
| `POST` | `/api/sync/pull`   | Triggers an immediate pull. Returns `{ok:true}`. |

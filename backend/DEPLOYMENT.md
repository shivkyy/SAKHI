# SAKHI Live Tracking Service — Deployment & Operations Guide

This directory contains the production-ready microservice that powers real-time live location tracking for the SAKHI Android Women's Safety System.

---

## 1. Local Development & Testing

The server is written in vanilla Node.js (v18+) with **zero external npm dependencies**, making it lightweight (<15MB RAM footprint), reliable, and instant to start.

### Run locally:
```bash
cd backend
node server.js
```
The server will bind to `http://0.0.0.0:3000`.

### Run automated verification test suite:
```bash
node --test backend/tests/server.test.js
```
All 7 integration tests run in under 100ms.

---

## 2. Cloud Deployment Options

### Option A: Render (Free Web Service) — Recommended
1. Push this repository to GitHub.
2. Sign in to [dashboard.render.com](https://dashboard.render.com/).
3. Click **New +** &rarr; **Blueprint**, or **Web Service**.
4. Select your repository and specify:
   - **Root Directory**: `backend`
   - **Runtime**: `Node`
   - **Build Command**: *(leave empty)*
   - **Start Command**: `node server.js`
   - **Health Check Path**: `/health`
5. Click **Create Web Service**. Render provides a free HTTPS URL: `https://sakhi-live-tracking-xxxx.onrender.com`.

### Option B: Railway
1. Install Railway CLI (`npm i -g @railway/cli`) or use [railway.app](https://railway.app).
2. Run `railway up` from the `backend/` directory.
3. Railway automatically detects `railway.json` and provisions an HTTPS endpoint.

### Option C: Fly.io (Docker)
1. Install flyctl: `curl -L https://fly.io/install.sh | sh`
2. Run `fly launch` in `backend/`. Fly will use the bundled `Dockerfile`.
3. Run `fly deploy`.

---

## 3. Connecting to the SAKHI Android Application

1. Open the **SAKHI Android App** on your device.
2. Navigate to **Settings** &rarr; **Live Tracking Server Configuration**.
3. Toggle **Enable Live Web Tracking** to **ON**.
4. Enter your deployed server URL (e.g. `https://sakhi-live-tracking-xxxx.onrender.com` or local testing LAN IP `http://192.168.1.50:3000`).
5. Tap **Test Server Connection** to verify end-to-end communication.

---

## 4. Security Architecture & Invariants

| Component | Protection Mechanism |
| :--- | :--- |
| **Reader Tracking Token** | 256-bit cryptographically random token (`crypto.randomBytes(32)`). Shared via SMS only with trusted contacts. Readers have read-only access. |
| **Writer Key** | 256-bit private key kept only in the Android device's memory. Required in `X-Writer-Key` header to upload coordinates or end sessions. |
| **Session Expiration** | Emergency sessions expire automatically after 2 hours. |
| **Data Retention** | All coordinates and sessions are permanently purged after 24 hours. |
| **Fail-Safe Offline Priority** | If backend network fails, the Android app gracefully falls back to direct Google Maps SMS without delaying emergency dispatch. |

const http = require('http');
const fs = require('fs');
const path = require('path');
const url = require('url');
const SessionStore = require('./sessionStore');

const PORT = parseInt(process.env.PORT || '3000', 10);
const HOST = process.env.HOST || '0.0.0.0';
const BASE_URL_OVERRIDE = process.env.PUBLIC_BASE_URL || null;

const sessionStore = new SessionStore({
  storagePath: process.env.DATA_PATH || path.join(__dirname, 'data', 'sessions.json'),
  ttlMinutes: parseInt(process.env.SESSION_TTL_MINUTES || '120', 10)
});

// MIME mapping for static assets
const MIME_TYPES = {
  '.html': 'text/html; charset=utf-8',
  '.css': 'text/css; charset=utf-8',
  '.js': 'application/javascript; charset=utf-8',
  '.json': 'application/json; charset=utf-8',
  '.png': 'image/png',
  '.jpg': 'image/jpeg',
  '.svg': 'image/svg+xml',
  '.ico': 'image/x-icon'
};

function getBaseUrl(req) {
  if (BASE_URL_OVERRIDE) {
    return BASE_URL_OVERRIDE.replace(/\/+$/, '');
  }
  const protocol = req.headers['x-forwarded-proto'] || 'http';
  const host = req.headers['x-forwarded-host'] || req.headers.host || `localhost:${PORT}`;
  return `${protocol}://${host}`;
}

function parseJsonBody(req) {
  return new Promise((resolve, reject) => {
    let raw = '';
    req.on('data', chunk => {
      raw += chunk;
      if (raw.length > 1024 * 64) {
        // Guard against overly large payloads (64KB max)
        req.destroy();
        reject(new Error('Payload Too Large'));
      }
    });
    req.on('end', () => {
      if (!raw.trim()) {
        resolve({});
        return;
      }
      try {
        const parsed = JSON.parse(raw);
        resolve(parsed);
      } catch (err) {
        const error = new Error('Invalid JSON format');
        error.status = 400;
        reject(error);
      }
    });
    req.on('error', reject);
  });
}

function sendJson(res, statusCode, data) {
  const payload = JSON.stringify(data);
  res.writeHead(statusCode, {
    'Content-Type': 'application/json; charset=utf-8',
    'Content-Length': Buffer.byteLength(payload),
    'Access-Control-Allow-Origin': '*',
    'Access-Control-Allow-Methods': 'GET, POST, OPTIONS',
    'Access-Control-Allow-Headers': 'Content-Type, X-Writer-Key, Authorization',
    'X-Content-Type-Options': 'nosniff',
    'X-Frame-Options': 'DENY'
  });
  res.end(payload);
}

function sendError(res, err) {
  const status = err.status || 500;
  sendJson(res, status, {
    success: false,
    error: err.message || 'Internal Server Error',
    code: err.code || 'INTERNAL_ERROR'
  });
}

function serveStaticFile(res, filePath) {
  fs.stat(filePath, (err, stats) => {
    if (err || !stats.isFile()) {
      res.writeHead(404, { 'Content-Type': 'text/plain; charset=utf-8' });
      res.end('404 Not Found');
      return;
    }

    const ext = path.extname(filePath).toLowerCase();
    const contentType = MIME_TYPES[ext] || 'application/octet-stream';

    // Permissive CSP for Leaflet OSM map scripts & tiles
    const headers = {
      'Content-Type': contentType,
      'Content-Length': stats.size,
      'X-Content-Type-Options': 'nosniff',
      'X-Frame-Options': 'SAMEORIGIN',
      'Content-Security-Policy': "default-src 'self' 'unsafe-inline' https://unpkg.com https://*.tile.openstreetmap.org https://maps.google.com data:; img-src 'self' data: https://*.tile.openstreetmap.org https://unpkg.com;"
    };

    res.writeHead(200, headers);
    fs.createReadStream(filePath).pipe(res);
  });
}

const server = http.createServer(async (req, res) => {
  const parsedUrl = new URL(req.url, 'http://localhost');
  const pathname = parsedUrl.pathname;
  const method = req.method.toUpperCase();

  // Handle CORS preflight
  if (method === 'OPTIONS') {
    res.writeHead(204, {
      'Access-Control-Allow-Origin': '*',
      'Access-Control-Allow-Methods': 'GET, POST, OPTIONS',
      'Access-Control-Allow-Headers': 'Content-Type, X-Writer-Key, Authorization',
      'Access-Control-Max-Age': '86400'
    });
    res.end();
    return;
  }

  // --- API Routes ---

  // Health check
  if (pathname === '/health' && method === 'GET') {
    sendJson(res, 200, {
      status: 'ok',
      service: 'sakhi-live-tracking',
      timestamp: Date.now(),
      activeSessions: sessionStore.sessions.size
    });
    return;
  }

  // Create Emergency Tracking Session
  // POST /api/sessions
  if (pathname === '/api/sessions' && method === 'POST') {
    try {
      const body = await parseJsonBody(req);
      const result = sessionStore.createSession({
        initialLocation: body.initialLocation,
        ttlMinutes: body.ttlMinutes
      });

      const baseUrl = getBaseUrl(req);
      const trackingUrl = `${baseUrl}/track/${result.sessionId}?token=${result.trackingToken}`;

      sendJson(res, 201, {
        success: true,
        sessionId: result.sessionId,
        trackingToken: result.trackingToken,
        writerKey: result.writerKey,
        trackingUrl,
        expiresAt: result.expiresAt
      });
    } catch (err) {
      sendError(res, err);
    }
    return;
  }

  // Session ID subroutes: /api/sessions/:id/...
  const sessionRouteMatch = pathname.match(/^\/api\/sessions\/([a-f0-9]{16,64})(\/.*)?$/);
  if (sessionRouteMatch) {
    const sessionId = sessionRouteMatch[1];
    const subRoute = sessionRouteMatch[2] || '';

    // POST /api/sessions/:id/location
    if (subRoute === '/location' && method === 'POST') {
      try {
        const body = await parseJsonBody(req);
        const writerKey = req.headers['x-writer-key'] || body.writerKey;
        const location = sessionStore.updateLocation(sessionId, writerKey, body);

        sendJson(res, 200, {
          success: true,
          sessionId,
          currentLocation: location
        });
      } catch (err) {
        sendError(res, err);
      }
      return;
    }

    // POST /api/sessions/:id/cancel
    if (subRoute === '/cancel' && method === 'POST') {
      try {
        const body = await parseJsonBody(req);
        const writerKey = req.headers['x-writer-key'] || body.writerKey;
        const result = sessionStore.cancelSession(sessionId, writerKey, body.reason);

        sendJson(res, 200, {
          success: true,
          message: 'Emergency session safely ended',
          ...result
        });
      } catch (err) {
        sendError(res, err);
      }
      return;
    }

    // GET /api/sessions/:id (Public tracking info)
    if (subRoute === '' && method === 'GET') {
      try {
        const token = parsedUrl.searchParams.get('token') || (req.headers.authorization ? req.headers.authorization.replace(/^Bearer\s+/i, '') : null);
        const session = sessionStore.getSessionForReader(sessionId, token);

        if (!session) {
          sendJson(res, 404, { success: false, error: 'Session not found', code: 'SESSION_NOT_FOUND' });
          return;
        }

        sendJson(res, 200, {
          success: true,
          session
        });
      } catch (err) {
        sendError(res, err);
      }
      return;
    }

    // GET /api/sessions/:id/stream (Server-Sent Events Real-Time Stream)
    if (subRoute === '/stream' && method === 'GET') {
      try {
        const token = parsedUrl.searchParams.get('token');
        const initialSession = sessionStore.getSessionForReader(sessionId, token);

        if (!initialSession) {
          sendJson(res, 404, { success: false, error: 'Session not found', code: 'SESSION_NOT_FOUND' });
          return;
        }

        res.writeHead(200, {
          'Content-Type': 'text/event-stream; charset=utf-8',
          'Cache-Control': 'no-cache, no-transform',
          'Connection': 'keep-alive',
          'Access-Control-Allow-Origin': '*',
          'X-Accel-Buffering': 'no' // Prevent Nginx/reverse proxy buffering
        });

        // Send initial state immediately
        res.write(`event: initial\ndata: ${JSON.stringify(initialSession)}\n\n`);

        const onLocationUpdate = (data) => {
          res.write(`event: location\ndata: ${JSON.stringify(data)}\n\n`);
        };

        const onSessionEnded = (data) => {
          res.write(`event: ended\ndata: ${JSON.stringify(data)}\n\n`);
        };

        sessionStore.on(`update:${sessionId}`, onLocationUpdate);
        sessionStore.on(`ended:${sessionId}`, onSessionEnded);

        // Heartbeat keep-alive every 15 seconds
        const heartbeat = setInterval(() => {
          res.write(': keep-alive ping\n\n');
        }, 15000);

        req.on('close', () => {
          clearInterval(heartbeat);
          sessionStore.removeListener(`update:${sessionId}`, onLocationUpdate);
          sessionStore.removeListener(`ended:${sessionId}`, onSessionEnded);
        });
      } catch (err) {
        sendError(res, err);
      }
      return;
    }
  }

  // --- Web App Routes ---

  // Tracking page route: /track/:sessionId OR /track?id=...
  if (pathname.startsWith('/track')) {
    const trackFilePath = path.join(__dirname, 'public', 'track.html');
    serveStaticFile(res, trackFilePath);
    return;
  }

  // Static Assets serving
  let staticPath = path.join(__dirname, 'public', pathname === '/' ? 'index.html' : pathname);
  // Prevent directory traversal
  const resolvedPath = path.resolve(staticPath);
  const publicDir = path.resolve(path.join(__dirname, 'public'));

  if (resolvedPath.startsWith(publicDir)) {
    fs.stat(resolvedPath, (err, stats) => {
      if (!err && stats.isFile()) {
        serveStaticFile(res, resolvedPath);
      } else {
        // Fallback to track.html for SPA sub-routes
        const trackHtml = path.join(__dirname, 'public', 'track.html');
        serveStaticFile(res, trackHtml);
      }
    });
  } else {
    res.writeHead(403, { 'Content-Type': 'text/plain' });
    res.end('Forbidden');
  }
});

if (require.main === module) {
  server.listen(PORT, HOST, () => {
    console.log(`[SAKHI Tracking Server] Listening on http://${HOST}:${PORT}`);
  });
}

module.exports = { server, sessionStore };

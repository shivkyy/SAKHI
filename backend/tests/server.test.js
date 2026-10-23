const test = require('node:test');
const assert = require('node:assert/strict');
const { PassThrough } = require('node:stream');
const path = require('node:path');
const fs = require('node:fs');
const { server, sessionStore } = require('../server');

function makeRequest(server, { method = 'GET', path = '/', headers = {}, body = null }) {
  return new Promise((resolve, reject) => {
    const req = new PassThrough();
    req.method = method;
    req.url = path;
    req.headers = {
      host: 'localhost:3000',
      ...Object.fromEntries(
        Object.entries(headers).map(([k, v]) => [k.toLowerCase(), v])
      )
    };

    const res = new PassThrough();
    res.statusCode = 200;
    res.headers = {};

    res.writeHead = function (status, h = {}) {
      res.statusCode = status;
      Object.assign(res.headers, h);
      return res;
    };

    const chunks = [];
    res.on('data', chunk => chunks.push(chunk));
    res.on('end', () => {
      const raw = Buffer.concat(chunks).toString();
      let json = null;
      try {
        json = JSON.parse(raw);
      } catch {}
      resolve({
        statusCode: res.statusCode,
        headers: res.headers,
        body: raw,
        json
      });
    });
    res.on('error', reject);

    server.emit('request', req, res);

    if (body) {
      const payload = typeof body === 'string' ? body : JSON.stringify(body);
      req.headers['content-length'] = Buffer.byteLength(payload).toString();
      req.write(payload);
    }
    req.end();
  });
}

test('Health Check: GET /health returns 200 OK and status', async () => {
  const res = await makeRequest(server, { path: '/health', method: 'GET' });
  assert.equal(res.statusCode, 200);
  assert.equal(res.json.status, 'ok');
  assert.equal(res.json.service, 'sakhi-live-tracking');
});

test('Create Session: POST /api/sessions creates valid emergency session', async () => {
  const payload = {
    initialLocation: {
      latitude: 28.5355,
      longitude: 77.3910,
      accuracy: 12.5,
      timestamp: Date.now()
    }
  };

  const res = await makeRequest(server, {
    path: '/api/sessions',
    method: 'POST',
    headers: { 'Content-Type': 'application/json' },
    body: payload
  });

  assert.equal(res.statusCode, 201);
  assert.equal(res.json.success, true);
  assert.ok(res.json.sessionId, 'Should return sessionId');
  assert.ok(res.json.trackingToken, 'Should return trackingToken');
  assert.ok(res.json.writerKey, 'Should return writerKey');
  assert.ok(res.json.trackingUrl.includes(res.json.sessionId));
  assert.ok(res.json.trackingUrl.includes(res.json.trackingToken));
});

test('Create Session: Rejects invalid coordinates with 400 Bad Request', async () => {
  const payload = {
    initialLocation: {
      latitude: 'not-a-number',
      longitude: 77.3910
    }
  };

  const res = await makeRequest(server, {
    path: '/api/sessions',
    method: 'POST',
    headers: { 'Content-Type': 'application/json' },
    body: payload
  });

  assert.equal(res.statusCode, 400);
  assert.equal(res.json.success, false);
});

test('Location Update: Valid writerKey succeeds, invalid writerKey fails (403)', async () => {
  // 1. Create session
  const createRes = await makeRequest(server, {
    path: '/api/sessions',
    method: 'POST',
    headers: { 'Content-Type': 'application/json' },
    body: {
      initialLocation: { latitude: 28.5355, longitude: 77.3910, accuracy: 10 }
    }
  });
  const { sessionId, writerKey } = createRes.json;

  // 2. Update with WRONG writerKey
  const badRes = await makeRequest(server, {
    path: `/api/sessions/${sessionId}/location`,
    method: 'POST',
    headers: {
      'Content-Type': 'application/json',
      'X-Writer-Key': 'wrong-key-456'
    },
    body: { latitude: 28.5360, longitude: 77.3915, accuracy: 8 }
  });

  assert.equal(badRes.statusCode, 403);
  assert.equal(badRes.json.code, 'INVALID_WRITER_KEY');

  // 3. Update with VALID writerKey
  const goodRes = await makeRequest(server, {
    path: `/api/sessions/${sessionId}/location`,
    method: 'POST',
    headers: {
      'Content-Type': 'application/json',
      'X-Writer-Key': writerKey
    },
    body: { latitude: 28.5360, longitude: 77.3915, accuracy: 8 }
  });

  assert.equal(goodRes.statusCode, 200);
  assert.equal(goodRes.json.success, true);
  assert.equal(goodRes.json.currentLocation.latitude, 28.5360);
});

test('Reader Access Control: Token verification on GET /api/sessions/:id', async () => {
  const createRes = await makeRequest(server, {
    path: '/api/sessions',
    method: 'POST',
    headers: { 'Content-Type': 'application/json' },
    body: {
      initialLocation: { latitude: 28.5355, longitude: 77.3910 }
    }
  });
  const { sessionId, trackingToken } = createRes.json;

  // Missing token -> 401
  const noTokenRes = await makeRequest(server, { path: `/api/sessions/${sessionId}`, method: 'GET' });
  assert.equal(noTokenRes.statusCode, 401);

  // Wrong token -> 403
  const badTokenRes = await makeRequest(server, { path: `/api/sessions/${sessionId}?token=wrong-token`, method: 'GET' });
  assert.equal(badTokenRes.statusCode, 403);

  // Correct token -> 200
  const goodTokenRes = await makeRequest(server, { path: `/api/sessions/${sessionId}?token=${trackingToken}`, method: 'GET' });
  assert.equal(goodTokenRes.statusCode, 200);
  assert.equal(goodTokenRes.json.session.sessionId, sessionId);
  assert.equal(goodTokenRes.json.session.status, 'ACTIVE');
  // Writer key must NEVER be leaked to reader
  assert.equal(goodTokenRes.json.session.writerKey, undefined);
});

test('Cancel Session: Disarm marks session as ENDED', async () => {
  const createRes = await makeRequest(server, {
    path: '/api/sessions',
    method: 'POST',
    headers: { 'Content-Type': 'application/json' },
    body: {
      initialLocation: { latitude: 28.5355, longitude: 77.3910 }
    }
  });
  const { sessionId, writerKey, trackingToken } = createRes.json;

  // Cancel with valid writer key
  const cancelRes = await makeRequest(server, {
    path: `/api/sessions/${sessionId}/cancel`,
    method: 'POST',
    headers: {
      'Content-Type': 'application/json',
      'X-Writer-Key': writerKey
    },
    body: { reason: 'User disarmed with PIN' }
  });

  assert.equal(cancelRes.statusCode, 200);
  assert.equal(cancelRes.json.status, 'ENDED');

  // Verify reader sees ENDED
  const readRes = await makeRequest(server, { path: `/api/sessions/${sessionId}?token=${trackingToken}`, method: 'GET' });
  assert.equal(readRes.json.session.status, 'ENDED');
  assert.equal(readRes.json.session.endedReason, 'User disarmed with PIN');
});

test('SSE Real-Time Stream: Emits initial and location events', async () => {
  const createRes = await makeRequest(server, {
    path: '/api/sessions',
    method: 'POST',
    headers: { 'Content-Type': 'application/json' },
    body: {
      initialLocation: { latitude: 28.5355, longitude: 77.3910 }
    }
  });
  const { sessionId, trackingToken, writerKey } = createRes.json;

  let receivedInitial = false;
  let receivedLocation = false;

  await new Promise((resolve) => {
    const req = new PassThrough();
    req.method = 'GET';
    req.url = `/api/sessions/${sessionId}/stream?token=${trackingToken}`;
    req.headers = { host: 'localhost:3000' };

    const res = new PassThrough();
    res.writeHead = function (status, h = {}) {
      assert.equal(status, 200);
      assert.equal(h['Content-Type'], 'text/event-stream; charset=utf-8');
      return res;
    };

    res.on('data', chunk => {
      const text = chunk.toString();
      if (text.includes('event: initial')) {
        receivedInitial = true;
        // Trigger location update
        makeRequest(server, {
          path: `/api/sessions/${sessionId}/location`,
          method: 'POST',
          headers: { 'Content-Type': 'application/json', 'X-Writer-Key': writerKey },
          body: { latitude: 28.5400, longitude: 77.3950 }
        });
      }
      if (text.includes('event: location')) {
        receivedLocation = true;
        req.emit('close');
        resolve();
      }
    });

    server.emit('request', req, res);
    req.end();
  });

  assert.ok(receivedInitial, 'Should receive initial event');
  assert.ok(receivedLocation, 'Should receive location update event');
});

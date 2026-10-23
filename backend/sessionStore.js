const crypto = require('crypto');
const fs = require('fs');
const path = require('path');
const EventEmitter = require('events');

class SessionStore extends EventEmitter {
  constructor(options = {}) {
    super();
    this.storagePath = options.storagePath || path.join(__dirname, 'data', 'sessions.json');
    this.defaultTtlMs = (options.ttlMinutes || 120) * 60 * 1000; // 2 hours
    this.staleThresholdMs = 2 * 60 * 1000; // 2 minutes
    this.sessions = new Map();

    this.ensureStorageDir();
    this.loadFromDisk();

    // Periodic cleanup of sessions older than 24 hours
    this.cleanupInterval = setInterval(() => {
      this.cleanupExpiredSessions();
    }, 15 * 60 * 1000);
    if (this.cleanupInterval.unref) this.cleanupInterval.unref();
  }

  ensureStorageDir() {
    try {
      const dir = path.dirname(this.storagePath);
      if (!fs.existsSync(dir)) {
        fs.mkdirSync(dir, { recursive: true });
      }
    } catch (err) {
      console.error('[SessionStore] Failed to ensure storage directory:', err.message);
    }
  }

  loadFromDisk() {
    try {
      if (fs.existsSync(this.storagePath)) {
        const raw = fs.readFileSync(this.storagePath, 'utf8');
        const data = JSON.parse(raw);
        if (Array.isArray(data)) {
          for (const s of data) {
            if (s && s.id) {
              this.sessions.set(s.id, s);
            }
          }
          console.log(`[SessionStore] Loaded ${this.sessions.size} sessions from disk.`);
        }
      }
    } catch (err) {
      console.warn('[SessionStore] Could not load sessions from disk, starting empty:', err.message);
    }
  }

  saveToDisk() {
    try {
      const list = Array.from(this.sessions.values());
      const tempPath = `${this.storagePath}.tmp`;
      fs.writeFileSync(tempPath, JSON.stringify(list, null, 2), 'utf8');
      fs.renameSync(tempPath, this.storagePath);
    } catch (err) {
      console.error('[SessionStore] Failed to persist sessions:', err.message);
    }
  }

  createSession({ initialLocation = null, ttlMinutes = null } = {}) {
    const id = crypto.randomBytes(16).toString('hex');
    const trackingToken = crypto.randomBytes(32).toString('hex');
    const writerKey = crypto.randomBytes(32).toString('hex');
    const now = Date.now();
    const ttlMs = ttlMinutes ? ttlMinutes * 60 * 1000 : this.defaultTtlMs;
    const expiresAt = now + ttlMs;

    const locationHistory = [];
    let currentLocation = null;

    if (initialLocation) {
      const lat = Number(initialLocation.latitude);
      const lng = Number(initialLocation.longitude);
      if (isNaN(lat) || lat < -90 || lat > 90 || isNaN(lng) || lng < -180 || lng > 180) {
        const err = new Error('Invalid initial coordinates: latitude must be between -90 and 90, longitude between -180 and 180');
        err.code = 'INVALID_COORDINATES';
        err.status = 400;
        throw err;
      }
      currentLocation = {
        latitude: lat,
        longitude: lng,
        accuracy: initialLocation.accuracy != null ? Number(initialLocation.accuracy) : null,
        timestamp: initialLocation.timestamp ? Number(initialLocation.timestamp) : now,
        address: initialLocation.address || null,
        lastUpdated: now
      };
      locationHistory.push({
        latitude: currentLocation.latitude,
        longitude: currentLocation.longitude,
        accuracy: currentLocation.accuracy,
        timestamp: currentLocation.timestamp
      });
    }

    const session = {
      id,
      trackingToken,
      writerKey,
      status: 'ACTIVE', // ACTIVE | CANCELLED | EXPIRED
      createdAt: now,
      expiresAt,
      endedAt: null,
      cancellationReason: null,
      currentLocation,
      locationHistory
    };

    this.sessions.set(id, session);
    this.saveToDisk();

    return {
      sessionId: id,
      trackingToken,
      writerKey,
      expiresAt,
      session
    };
  }

  updateLocation(sessionId, writerKey, locationData) {
    const session = this.sessions.get(sessionId);
    if (!session) {
      const err = new Error('Session not found');
      err.code = 'SESSION_NOT_FOUND';
      err.status = 404;
      throw err;
    }

    // Authenticate writer
    if (!writerKey) {
      const err = new Error('Missing writer key');
      err.code = 'MISSING_WRITER_KEY';
      err.status = 401;
      throw err;
    }
    if (session.writerKey !== writerKey) {
      const err = new Error('Invalid writer key');
      err.code = 'INVALID_WRITER_KEY';
      err.status = 403;
      throw err;
    }

    const now = Date.now();

    // Check expiration
    if (now > session.expiresAt) {
      session.status = 'EXPIRED';
      this.saveToDisk();
      const err = new Error('Session has expired');
      err.code = 'SESSION_EXPIRED';
      err.status = 410;
      throw err;
    }

    // Check if session is already cancelled/ended
    if (session.status !== 'ACTIVE') {
      const err = new Error(`Cannot update location: session is ${session.status}`);
      err.code = 'SESSION_INACTIVE';
      err.status = 400;
      throw err;
    }

    // Validate coordinates
    const lat = Number(locationData.latitude);
    const lng = Number(locationData.longitude);

    if (isNaN(lat) || lat < -90 || lat > 90) {
      const err = new Error('Invalid latitude: must be between -90 and 90');
      err.code = 'INVALID_COORDINATES';
      err.status = 400;
      throw err;
    }

    if (isNaN(lng) || lng < -180 || lng > 180) {
      const err = new Error('Invalid longitude: must be between -180 and 180');
      err.code = 'INVALID_COORDINATES';
      err.status = 400;
      throw err;
    }

    const accuracy = locationData.accuracy != null ? Number(locationData.accuracy) : null;
    const timestamp = locationData.timestamp ? Number(locationData.timestamp) : now;
    const address = locationData.address || session.currentLocation?.address || null;

    session.currentLocation = {
      latitude: lat,
      longitude: lng,
      accuracy,
      timestamp,
      address,
      lastUpdated: now
    };

    // Keep up to 120 historical breadcrumb fixes
    session.locationHistory.push({
      latitude: lat,
      longitude: lng,
      accuracy,
      timestamp
    });

    if (session.locationHistory.length > 120) {
      session.locationHistory.shift();
    }

    this.saveToDisk();

    // Notify real-time SSE stream listeners
    this.emit(`update:${sessionId}`, this.formatReaderPayload(session));

    return session.currentLocation;
  }

  cancelSession(sessionId, writerKey, reason = 'USER_RESOLVED_SAFE') {
    const session = this.sessions.get(sessionId);
    if (!session) {
      const err = new Error('Session not found');
      err.code = 'SESSION_NOT_FOUND';
      err.status = 404;
      throw err;
    }

    if (!writerKey) {
      const err = new Error('Missing writer key');
      err.code = 'MISSING_WRITER_KEY';
      err.status = 401;
      throw err;
    }
    if (session.writerKey !== writerKey) {
      const err = new Error('Invalid writer key');
      err.code = 'INVALID_WRITER_KEY';
      err.status = 403;
      throw err;
    }

    session.status = 'ENDED';
    session.endedAt = Date.now();
    session.cancellationReason = reason;

    this.saveToDisk();

    // Notify real-time listeners
    this.emit(`ended:${sessionId}`, this.formatReaderPayload(session));

    return {
      success: true,
      sessionId: session.id,
      status: session.status,
      endedAt: session.endedAt
    };
  }

  getSessionForReader(sessionId, token) {
    const session = this.sessions.get(sessionId);
    if (!session) {
      return null;
    }

    // Validate reader token
    if (!token) {
      const err = new Error('Missing tracking token');
      err.code = 'MISSING_TOKEN';
      err.status = 401;
      throw err;
    }
    if (session.trackingToken !== token) {
      const err = new Error('Invalid tracking token');
      err.code = 'INVALID_TOKEN';
      err.status = 403;
      throw err;
    }

    // Check expiration
    if (Date.now() > session.expiresAt && session.status === 'ACTIVE') {
      session.status = 'EXPIRED';
      this.saveToDisk();
    }

    return this.formatReaderPayload(session);
  }

  formatReaderPayload(session) {
    const now = Date.now();
    const isExpired = now > session.expiresAt;
    const effectiveStatus = session.status === 'ACTIVE' && isExpired ? 'EXPIRED' : session.status;

    let isStale = false;
    if (session.currentLocation) {
      const fixAge = now - session.currentLocation.timestamp;
      isStale = fixAge > this.staleThresholdMs;
    }

    return {
      sessionId: session.id,
      status: effectiveStatus,
      createdAt: session.createdAt,
      expiresAt: session.expiresAt,
      endedAt: session.endedAt,
      cancellationReason: session.cancellationReason,
      endedReason: session.cancellationReason,
      currentLocation: session.currentLocation ? {
        ...session.currentLocation,
        isStale
      } : null,
      breadcrumbs: session.locationHistory || [],
      locationHistory: session.locationHistory || [],
      googleMapsUrl: session.currentLocation
        ? `https://maps.google.com/?q=${session.currentLocation.latitude},${session.currentLocation.longitude}`
        : null
    };
  }

  cleanupExpiredSessions() {
    const now = Date.now();
    const purgeThreshold = 24 * 60 * 60 * 1000; // 24 hours
    let changed = false;

    for (const [id, session] of this.sessions.entries()) {
      if (now - session.expiresAt > purgeThreshold) {
        this.sessions.delete(id);
        changed = true;
      }
    }

    if (changed) {
      this.saveToDisk();
      console.log(`[SessionStore] Purged old sessions. Active count: ${this.sessions.size}`);
    }
  }
}

module.exports = SessionStore;

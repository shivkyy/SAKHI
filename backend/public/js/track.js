/**
 * SAKHI Live Emergency Location Tracker
 * Client-side real-time map & telemetry controller
 */

(function () {
  'use strict';

  // --- State ---
  let map = null;
  let victimMarker = null;
  let accuracyCircle = null;
  let routePolyline = null;
  let breadcrumbMarkers = [];
  let lastLat = null;
  let lastLng = null;
  let lastFixTimestamp = null;
  let lastSyncTimestamp = null;
  let pollInterval = null;
  let eventSource = null;
  let isMapInitialized = false;
  let autoCenter = true;

  // --- DOM Elements ---
  const elStatusPill = document.getElementById('statusPill');
  const elStatusText = document.getElementById('statusText');
  const elSessionIdLabel = document.getElementById('sessionIdLabel');
  const elConnStatusText = document.getElementById('connStatusText');
  const elConnDot = document.querySelector('.conn-dot');
  const elValCoords = document.getElementById('valCoords');
  const elValAccuracy = document.getElementById('valAccuracy');
  const elValBreadcrumbs = document.getElementById('valBreadcrumbs');
  const elValFixTime = document.getElementById('valFixTime');
  const elValFixRelative = document.getElementById('valFixRelative');
  const elValSyncTime = document.getElementById('valSyncTime');
  const elValSyncRelative = document.getElementById('valSyncRelative');
  const elValAddress = document.getElementById('valAddress');
  const elBtnRecenter = document.getElementById('btnRecenter');
  const elBtnCopyCoords = document.getElementById('btnCopyCoords');
  const elBtnGoogleMaps = document.getElementById('btnGoogleMaps');
  const elBtnShareLink = document.getElementById('btnShareLink');
  const elStaleAlert = document.getElementById('staleAlert');
  const elEndedAlert = document.getElementById('endedAlert');
  const elEndedAlertText = document.getElementById('endedAlertText');
  const elExpiredAlert = document.getElementById('expiredAlert');
  const elMapFallback = document.getElementById('mapFallbackBanner');

  // --- Session Parameters ---
  function parseSessionParams() {
    const searchParams = new URLSearchParams(window.location.search);
    let sessionId = searchParams.get('id') || searchParams.get('session');
    
    // Check if session ID is in pathname: /track/:id
    const pathMatch = window.location.pathname.match(/\/track\/([a-f0-9]{16,64})/i);
    if (pathMatch) {
      sessionId = pathMatch[1];
    }
    
    const token = searchParams.get('token') || '';
    return { sessionId, token };
  }

  const { sessionId, token } = parseSessionParams();

  if (!sessionId) {
    if (elStatusText) elStatusText.textContent = 'INVALID TRACKING LINK';
    if (elValCoords) elValCoords.textContent = 'Missing session ID';
    if (elConnStatusText) elConnStatusText.textContent = 'Invalid Link';
    if (elConnDot) {
      elConnDot.className = 'conn-dot disconnected';
    }
    return;
  }

  if (elSessionIdLabel) {
    elSessionIdLabel.textContent = `ID: ${sessionId.substring(0, 8)}...`;
  }

  // --- Leaflet Map Setup ---
  function initMap(initialLat, initialLng) {
    if (typeof L === 'undefined') {
      console.warn('[SAKHI] Leaflet library not loaded.');
      if (elMapFallback) elMapFallback.style.display = 'block';
      return;
    }

    try {
      const lat = initialLat || 28.6139; // Default center (e.g. New Delhi)
      const lng = initialLng || 77.2090;

      map = L.map('map', {
        zoomControl: true,
        attributionControl: true
      }).setView([lat, lng], 16);

      // OpenStreetMap Tile Layer
      const tileLayer = L.tileLayer('https://{s}.tile.openstreetmap.org/{z}/{x}/{y}.png', {
        maxZoom: 19,
        attribution: '&copy; <a href="https://www.openstreetmap.org/copyright" target="_blank">OpenStreetMap</a> contributors | SAKHI'
      });

      tileLayer.on('tileerror', function () {
        if (elMapFallback) elMapFallback.style.display = 'block';
      });

      tileLayer.addTo(map);

      // Breadcrumb polyline
      routePolyline = L.polyline([], {
        color: '#7C3AED',
        weight: 4,
        opacity: 0.85,
        lineJoin: 'round',
        dashArray: '4, 8'
      }).addTo(map);

      // Prevent map drag from fighting user manual exploration
      map.on('dragstart', () => { autoCenter = false; });

      isMapInitialized = true;
    } catch (err) {
      console.error('[SAKHI] Map init error:', err);
      if (elMapFallback) elMapFallback.style.display = 'block';
    }
  }

  // Custom Pulsing Victim Marker Icon
  function createVictimIcon() {
    return L.divIcon({
      className: 'custom-leaflet-marker',
      html: '<div class="victim-marker-container"><div class="victim-marker-pulse"></div><div class="victim-marker-pin"></div></div>',
      iconSize: [32, 32],
      iconAnchor: [16, 16],
      popupAnchor: [0, -16]
    });
  }

  // --- UI Update Helpers ---
  function formatTimestamp(ts) {
    if (!ts) return '--';
    const d = new Date(ts);
    return d.toLocaleTimeString([], { hour: '2-digit', minute: '2-digit', second: '2-digit' });
  }

  function getRelativeTime(ts) {
    if (!ts) return '--';
    const diffSec = Math.max(0, Math.floor((Date.now() - ts) / 1000));
    if (diffSec < 5) return 'just now';
    if (diffSec < 60) return `${diffSec}s ago`;
    const diffMin = Math.floor(diffSec / 60);
    if (diffMin < 60) return `${diffMin}m ago`;
    const diffHr = Math.floor(diffMin / 60);
    return `${diffHr}h ago`;
  }

  function updateRelativeTimes() {
    if (lastFixTimestamp && elValFixRelative) {
      elValFixRelative.textContent = getRelativeTime(lastFixTimestamp);
      
      // Check stale status (> 120 seconds)
      const diffSec = (Date.now() - lastFixTimestamp) / 1000;
      if (diffSec > 120 && elStaleAlert) {
        elStaleAlert.style.display = 'flex';
      } else if (elStaleAlert) {
        elStaleAlert.style.display = 'none';
      }
    }
    if (lastSyncTimestamp && elValSyncRelative) {
      elValSyncRelative.textContent = getRelativeTime(lastSyncTimestamp);
    }
  }

  setInterval(updateRelativeTimes, 2000);

  // Address lookup via OSM Nominatim (throttled)
  let lastGeocodeTime = 0;
  async function reverseGeocode(lat, lng) {
    const now = Date.now();
    if (now - lastGeocodeTime < 10000) return; // at most once every 10s
    lastGeocodeTime = now;

    try {
      const resp = await fetch(`https://nominatim.openstreetmap.org/reverse?format=json&lat=${lat}&lon=${lng}&zoom=18&addressdetails=1`, {
        headers: { 'Accept': 'application/json' }
      });
      if (resp.ok) {
        const data = await resp.json();
        if (data && data.display_name && elValAddress) {
          elValAddress.textContent = data.display_name;
        }
      }
    } catch {
      // Ignore network errors for external reverse geocoding
    }
  }

  // --- Render Telemetry & Location ---
  function applyLocationUpdate(loc, breadcrumbsCount) {
    if (!loc || typeof loc.latitude !== 'number' || typeof loc.longitude !== 'number') {
      return;
    }

    const lat = loc.latitude;
    const lng = loc.longitude;
    const accuracy = loc.accuracy || 15;
    const ts = loc.timestamp || Date.now();

    lastLat = lat;
    lastLng = lng;
    lastFixTimestamp = ts;
    lastSyncTimestamp = Date.now();

    // Text metrics
    if (elValCoords) {
      elValCoords.textContent = `${lat.toFixed(5)}, ${lng.toFixed(5)}`;
    }
    if (elValAccuracy) {
      elValAccuracy.textContent = `±${Math.round(accuracy)} meters`;
    }
    if (elValFixTime) {
      elValFixTime.textContent = formatTimestamp(ts);
    }
    if (elValSyncTime) {
      elValSyncTime.textContent = formatTimestamp(lastSyncTimestamp);
    }
    if (elValBreadcrumbs && breadcrumbsCount !== undefined) {
      elValBreadcrumbs.textContent = `${breadcrumbsCount} location point${breadcrumbsCount === 1 ? '' : 's'} logged`;
    }

    // Google Maps Link
    if (elBtnGoogleMaps) {
      elBtnGoogleMaps.href = `https://maps.google.com/?q=${lat},${lng}`;
    }

    // Reverse geocode
    reverseGeocode(lat, lng);

    // Map markers
    if (!isMapInitialized) {
      initMap(lat, lng);
    }

    if (map) {
      const latLng = [lat, lng];

      // Update or create victim marker
      if (!victimMarker) {
        victimMarker = L.marker(latLng, { icon: createVictimIcon(), zIndexOffset: 1000 }).addTo(map);
        victimMarker.bindPopup('<strong>SAKHI Wearable User</strong><br>Current Live Position');
      } else {
        victimMarker.setLatLng(latLng);
      }

      // Update or create accuracy circle
      if (!accuracyCircle) {
        accuracyCircle = L.circle(latLng, {
          radius: accuracy,
          color: '#EF4444',
          fillColor: '#EF4444',
          fillOpacity: 0.12,
          weight: 1.5
        }).addTo(map);
      } else {
        accuracyCircle.setLatLng(latLng);
        accuracyCircle.setRadius(accuracy);
      }

      // Breadcrumb path
      if (routePolyline) {
        routePolyline.addLatLng(latLng);
      }

      if (autoCenter) {
        map.panTo(latLng, { animate: true, duration: 1.0 });
      }
    }
  }

  function renderSessionState(session) {
    if (!session) return;

    // Status pill
    if (session.status === 'ENDED') {
      if (elStatusPill) elStatusPill.className = 'status-pill status-safe';
      if (elStatusText) elStatusText.textContent = 'EMERGENCY ENDED (SAFE)';
      if (elEndedAlert) elEndedAlert.style.display = 'flex';
      if (session.endedReason && elEndedAlertText) {
        elEndedAlertText.textContent = `Status: ${session.endedReason}`;
      }
    } else if (session.status === 'EXPIRED') {
      if (elStatusPill) elStatusPill.className = 'status-pill status-expired';
      if (elStatusText) elStatusText.textContent = 'SESSION EXPIRED';
      if (elExpiredAlert) elExpiredAlert.style.display = 'flex';
    } else {
      if (elStatusPill) elStatusPill.className = 'status-pill status-active';
      if (elStatusText) elStatusText.textContent = 'EMERGENCY ACTIVE';
    }

    // Breadcrumbs reconstruction
    if (session.breadcrumbs && Array.isArray(session.breadcrumbs) && map && routePolyline) {
      const latLngs = session.breadcrumbs.map(b => [b.latitude, b.longitude]);
      routePolyline.setLatLngs(latLngs);
    }

    if (session.currentLocation) {
      const totalPoints = session.breadcrumbs ? session.breadcrumbs.length : 1;
      applyLocationUpdate(session.currentLocation, totalPoints);
    }
  }

  // --- Network Connection (SSE with Polling Fallback) ---
  function connectEventSource() {
    const sseUrl = `/api/sessions/${sessionId}/stream?token=${encodeURIComponent(token)}`;

    if (eventSource) {
      eventSource.close();
    }

    eventSource = new EventSource(sseUrl);

    eventSource.onopen = function () {
      if (elConnStatusText) elConnStatusText.textContent = 'Live Connected';
      if (elConnDot) elConnDot.className = 'conn-dot live';
      // Clear fallback polling if active
      if (pollInterval) {
        clearInterval(pollInterval);
        pollInterval = null;
      }
    };

    eventSource.addEventListener('initial', function (e) {
      try {
        const session = JSON.parse(e.data);
        renderSessionState(session);
      } catch (err) {
        console.error('[SAKHI] Parse error on initial event:', err);
      }
    });

    eventSource.addEventListener('location', function (e) {
      try {
        const data = JSON.parse(e.data);
        if (data.location) {
          applyLocationUpdate(data.location, data.breadcrumbsCount);
        }
      } catch (err) {
        console.error('[SAKHI] Parse error on location event:', err);
      }
    });

    eventSource.addEventListener('ended', function (e) {
      try {
        const data = JSON.parse(e.data);
        if (elStatusPill) elStatusPill.className = 'status-pill status-safe';
        if (elStatusText) elStatusText.textContent = 'EMERGENCY ENDED (SAFE)';
        if (elEndedAlert) elEndedAlert.style.display = 'flex';
        if (data.reason && elEndedAlertText) {
          elEndedAlertText.textContent = `Status: ${data.reason}`;
        }
      } catch (err) {
        console.error('[SAKHI] Parse error on ended event:', err);
      }
    });

    eventSource.onerror = function () {
      if (elConnStatusText) elConnStatusText.textContent = 'Reconnecting...';
      if (elConnDot) elConnDot.className = 'conn-dot disconnected';
      // Trigger polling fallback
      startPollingFallback();
    };
  }

  async function fetchSessionData() {
    try {
      const res = await fetch(`/api/sessions/${sessionId}?token=${encodeURIComponent(token)}`);
      if (res.ok) {
        const data = await res.json();
        if (data && data.session) {
          renderSessionState(data.session);
          if (elConnStatusText) elConnStatusText.textContent = 'Synced';
          if (elConnDot) elConnDot.className = 'conn-dot live';
        }
      } else if (res.status === 404) {
        if (elStatusText) elStatusText.textContent = 'SESSION NOT FOUND';
      }
    } catch {
      if (elConnStatusText) elConnStatusText.textContent = 'Offline';
      if (elConnDot) elConnDot.className = 'conn-dot disconnected';
    }
  }

  function startPollingFallback() {
    if (pollInterval) return;
    fetchSessionData();
    pollInterval = setInterval(fetchSessionData, 10000);
  }

  // --- Interactive Buttons ---
  if (elBtnRecenter) {
    elBtnRecenter.addEventListener('click', () => {
      autoCenter = true;
      if (map && lastLat !== null && lastLng !== null) {
        map.flyTo([lastLat, lastLng], 17, { duration: 0.8 });
      }
    });
  }

  if (elBtnCopyCoords) {
    elBtnCopyCoords.addEventListener('click', async () => {
      if (lastLat === null || lastLng === null) return;
      const text = `${lastLat.toFixed(6)}, ${lastLng.toFixed(6)}`;
      try {
        await navigator.clipboard.writeText(text);
        const originalHtml = elBtnCopyCoords.innerHTML;
        elBtnCopyCoords.innerHTML = '<span style="color:#10B981;font-size:12px;font-weight:700;">COPIED</span>';
        setTimeout(() => { elBtnCopyCoords.innerHTML = originalHtml; }, 2000);
      } catch {
        alert(`Coordinates: ${text}`);
      }
    });
  }

  if (elBtnShareLink) {
    elBtnShareLink.addEventListener('click', async () => {
      const shareData = {
        title: 'SAKHI Emergency Live Location',
        text: 'Live emergency location tracking link for SAKHI safety wearable session.',
        url: window.location.href
      };

      if (navigator.share) {
        try {
          await navigator.share(shareData);
          return;
        } catch {
          // fallback to clipboard
        }
      }

      try {
        await navigator.clipboard.writeText(window.location.href);
        alert('Tracking link copied to clipboard!');
      } catch {
        prompt('Copy tracking link:', window.location.href);
      }
    });
  }

  // Start real-time connection
  connectEventSource();

})();

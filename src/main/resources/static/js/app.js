/* DRAS 2.0 EOC Command Center. Plain JavaScript; every number shown comes from the Spring Boot REST API. */
'use strict';

// ---------- helpers ----------
const $ = (s, r = document) => r.querySelector(s);
const esc = v => String(v ?? '').replace(/[&<>"']/g, c => ({'&':'&amp;','<':'&lt;','>':'&gt;','"':'&quot;',"'":'&#39;'}[c]));
const fmtTime = t => t ? new Date(t).toLocaleString() : '';
const view = () => $('#view');
let accessToken = null;
let signedInUser = null;
let authChecked = false;
let liveStreamController = null;
let liveStreamGeneration = 0;
let landingIntroPlayed = false;
let landingIntroCleanup = null;

function showPublicShell(page) {
  document.body.classList.add('auth-logged-out', 'public-mode');
  document.body.dataset.publicPage = page;
  $('#sidebar').hidden = true;
  $('main > header').hidden = true;
  $('#menuBtn').hidden = true;
  $('#authAction').hidden = true;
  $('#dbBadge').hidden = true;
}

function setPublicPath(path) {
  if (location.pathname !== path) history.pushState({}, '', path);
}

function stopLiveEventStream() {
  liveStreamGeneration++;
  liveStreamController?.abort();
  liveStreamController = null;
  document.documentElement.dataset.sseStatus = 'disconnected';
}

function startLiveEventStream() {
  if (!accessToken || !signedInUser || signedInUser.mustChangePassword) {
    stopLiveEventStream();
    return;
  }
  stopLiveEventStream();
  const generation = liveStreamGeneration;
  const controller = new AbortController();
  liveStreamController = controller;

  const setState = state => {
    if (generation !== liveStreamGeneration) return;
    document.documentElement.dataset.sseStatus = state;
    window.dispatchEvent(new CustomEvent('dras:sse-connection', { detail: { state } }));
  };
  const delay = milliseconds => new Promise(resolve => {
    let timer;
    const onAbort = () => { clearTimeout(timer); finish(); };
    const finish = () => {
      controller.signal.removeEventListener('abort', onAbort);
      resolve();
    };
    timer = setTimeout(finish, milliseconds);
    controller.signal.addEventListener('abort', onAbort, { once: true });
  });
  const consume = async response => {
    const reader = response.body.getReader();
    const decoder = new TextDecoder();
    let buffer = '';
    let eventName = 'message';
    let data = [];
    const dispatch = () => {
      if (data.length) {
        let payload = data.join('\n');
        try { payload = JSON.parse(payload); } catch { /* Keep valid plain-text SSE payloads available. */ }
        window.dispatchEvent(new CustomEvent('dras:operational-event', {
          detail: { type: eventName, payload }
        }));
      }
      eventName = 'message';
      data = [];
    };
    const line = value => {
      if (!value) { dispatch(); return; }
      if (value.startsWith(':')) return;
      const separator = value.indexOf(':');
      const field = separator < 0 ? value : value.slice(0, separator);
      const fieldValue = separator < 0 ? '' : value.slice(separator + 1).replace(/^ /, '');
      if (field === 'event') eventName = fieldValue;
      else if (field === 'data') data.push(fieldValue);
    };
    try {
      while (generation === liveStreamGeneration && !controller.signal.aborted) {
        const chunk = await reader.read();
        if (chunk.done) break;
        buffer += decoder.decode(chunk.value, { stream: true });
        const lines = buffer.split('\n');
        buffer = lines.pop() ?? '';
        lines.forEach(value => line(value.replace(/\r$/, '')));
      }
    } finally { reader.releaseLock(); }
  };

  void (async () => {
    let retryDelay = 1000;
    while (generation === liveStreamGeneration && !controller.signal.aborted) {
      try {
        const response = await fetch('/api/events/stream', {
          method: 'GET',
          headers: { Authorization: 'Bearer ' + accessToken, Accept: 'text/event-stream' },
          credentials: 'same-origin',
          cache: 'no-store',
          signal: controller.signal
        });
        if (response.status === 401) {
          try { setSession(await api('POST', '/api/auth/refresh', {}, false)); }
          catch { clearSession(); await render(); }
          return;
        }
        if (response.status === 403) {
          setState('forbidden');
          return;
        }
        if (!response.ok || !response.body) throw new Error(`SSE connection failed (${response.status})`);
        retryDelay = 1000;
        setState('connected');
        await consume(response);
        if (generation === liveStreamGeneration && !controller.signal.aborted) setState('reconnecting');
      } catch (error) {
        if (controller.signal.aborted || generation !== liveStreamGeneration) return;
        if (error.name === 'AbortError') return;
        setState('reconnecting');
      }
      await delay(retryDelay);
      retryDelay = Math.min(retryDelay * 2, 30000);
    }
  })();
}

async function api(method, url, body, retry = true) {
  const opts = { method, headers: {}, credentials: 'same-origin' };
  if (accessToken) opts.headers.Authorization = 'Bearer ' + accessToken;
  if (body !== undefined) { opts.headers['Content-Type'] = 'application/json'; opts.body = JSON.stringify(body); }
  let res;
  try { res = await fetch(url, opts); }
  catch (e) { throw new Error('Cannot reach the server: ' + e.message); }
  if (res.status === 204) return null;
  const text = await res.text();
  let data = null;
  try { data = text ? JSON.parse(text) : null; } catch { data = { message: text }; }
  if (res.status === 401 && retry && accessToken && !url.startsWith('/api/auth/')) {
    try {
      const renewed = await api('POST', '/api/auth/refresh', {}, false);
      setSession(renewed);
      return api(method, url, body, false);
    } catch { clearSession(); }
  }
  if (!res.ok) throw new Error((data && (data.message || data.error)) || ('HTTP ' + res.status));
  return data;
}
const GET = u => api('GET', u), POST = (u, b) => api('POST', u, b ?? {}), PUT = (u, b) => api('PUT', u, b), DEL = u => api('DELETE', u);

function toast(msg, kind = 'ok') {
  const el = document.createElement('div');
  el.className = 'toast ' + kind; el.textContent = msg;
  $('#toasts').appendChild(el);
  setTimeout(() => el.remove(), kind === 'err' ? 7000 : 3800);
}
/** Runs an async action, shows success/error toast, then refreshes the page. */
async function act(fn, okMsg, refresh = true) {
  try { const r = await fn(); if (okMsg) toast(typeof okMsg === 'function' ? okMsg(r) : okMsg, 'ok'); if (refresh) await render(); return r; }
  catch (e) { toast(e.message, 'err'); }
}
const pill = s => {
  const m = { ALLOCATED:'ok', COMPLETED:'info', ACTIVE:'ok', RELEASED:'info', PENDING:'warn', WAITING:'warn', PROCESSING:'info', REJECTED:'bad', ROLLED_BACK:'bad', ERROR:'bad' };
  return `<span class="pill ${m[s] || ''}">${esc(s)}</span>`;
};
function table(cols, rows, empty = 'No data') {
  if (!rows.length) return `<div class="empty">${esc(empty)}</div>`;
  return `<div class="tablewrap"><table><thead><tr>${cols.map(c => `<th class="${c.num ? 'num' : ''}">${esc(c.h)}</th>`).join('')}</tr></thead><tbody>` +
    rows.map(r => `<tr>${cols.map(c => `<td class="${c.num ? 'num' : ''}">${c.html ? c.html(r) : esc(c.v(r))}</td>`).join('')}</tr>`).join('') + '</tbody></table></div>';
}
function openModal(html) { $('#modalBody').innerHTML = html; $('#modal').classList.remove('hidden'); }
function closeModal() { $('#modal').classList.add('hidden'); }
$('#modal').addEventListener('click', e => { if (e.target.id === 'modal') closeModal(); });
const opts = (list, val, label, sel) => list.map(x => `<option value="${esc(x[val])}" ${String(x[val]) === String(sel) ? 'selected' : ''}>${esc(typeof label === 'function' ? label(x) : x[label])}</option>`).join('');
const val = id => document.getElementById(id).value;
const num = id => { const v = val(id); return v === '' ? null : Number(v); };

/** Client-side search + filter for a table: rows are re-rendered from the cached list. */
function filterBar(id, placeholder, selects = []) {
  return `<div class="toolbar"><input id="${id}-q" placeholder="${esc(placeholder)}" style="min-width:240px">` +
    selects.map(s => `<select id="${id}-${s.key}"><option value="">${esc(s.label)}</option>${s.options.map(o => `<option>${esc(o)}</option>`).join('')}</select>`).join('') + '</div>';
}
function wireFilter(id, selects, redraw) {
  const h = () => redraw($(`#${id}-q`).value.toLowerCase(), Object.fromEntries(selects.map(s => [s.key, $(`#${id}-${s.key}`).value])));
  $(`#${id}-q`).addEventListener('input', h);
  selects.forEach(s => $(`#${id}-${s.key}`).addEventListener('change', h));
}

// ---------- router ----------
const pages = {
  dashboard: ['Command Center', pgDashboard], inventory: ['Resource Inventory', pgInventory],
  organizations: ['Organization Directory', pgOrganizations], requests: ['Requests Queue', pgRequests],
  allocations: ['Allocation Center', pgAllocations], scheduling: ['Scheduling Simulator', pgScheduling],
  bankers: ["Banker's Algorithm Simulator", pgBankers], metrics: ['Performance Metrics', pgMetrics],
  system: ['System Status', pgSystem], approvals: ['Account Approvals', pgApprovals], users: ['User Management', pgUsers],
  members: ['Organization Members', pgMembers], alerts: ['Operational Alerts', pgAlerts], audit: ['Audit Logs', pgAudit]
};
const rolePages = {
  SYSTEM_ADMIN: ['dashboard','inventory','organizations','requests','allocations','scheduling','bankers','metrics','system','approvals','users','alerts','audit'],
  DISPATCHER: ['dashboard','inventory','organizations','requests','allocations','scheduling','bankers','metrics','system','alerts'],
  ORGANIZATION_MANAGER: ['dashboard','inventory','organizations','requests','allocations','metrics','members'],
  ORGANIZATION_MEMBER: ['dashboard','inventory','organizations','requests']
};
async function render() {
  if (!authChecked) {
    authChecked = true;
    try { setSession(await api('POST', '/api/auth/refresh', {}, false)); } catch { clearSession(); }
  }
  if (location.pathname === '/' || location.pathname === '/index.html') {
    showPublicShell('landing');
    await pgLanding();
    return;
  }
  if (location.pathname === '/login' || location.pathname === '/register') {
    showPublicShell('auth');
    await pgAuth(location.pathname === '/register' ? 'register' : 'login');
    return;
  }
  document.body.classList.remove('public-mode');
  delete document.body.dataset.publicPage;
  if (!accessToken) {
    history.replaceState({}, '', '/login');
    showPublicShell('auth');
    await pgAuth('login');
    return;
  }
  document.body.classList.remove('auth-logged-out');
  $('main > header').hidden = false;
  if (signedInUser?.mustChangePassword) { await pgPasswordChange(); return; }
  const key = (location.hash || '#dashboard').slice(1);
  const [title, fn] = pages[key] || pages.dashboard;
  $('#pageTitle').textContent = title;
  document.querySelectorAll('#nav a').forEach(a => a.classList.toggle('active', a.getAttribute('href') === '#' + key));
  $('#sidebar').classList.remove('open');
  if (!rolePages[signedInUser?.role]?.includes(key)) {
    view().innerHTML = '<div class="panel"><h2>Section access is restricted</h2><p class="muted">This section is not available to your account role.</p></div>';
    return;
  }
  view().className = 'view-transition';
  try { await fn(); } catch (e) { view().innerHTML = `<div class="panel"><h2>Operational Alert</h2><b>Could not load this page.</b><p class="muted">${esc(e.message)}</p></div>`; toast(e.message, 'err'); }
  pingHealth();
}
function setSession(session) {
  accessToken = session.accessToken;
  signedInUser = session.user;
  authChecked = true;
  const name = $('.user-profile-name');
  const role = $('.user-profile-role');
  const avatar = $('.user-profile-avatar');
  if (name) name.textContent = signedInUser.fullName;
  if (role) role.textContent = signedInUser.role.replaceAll('_', ' ');
  if (avatar) avatar.textContent = signedInUser.fullName.split(/\s+/).map(x => x[0]).join('').slice(0, 2).toUpperCase();
  document.body.classList.remove('auth-logged-out');
  $('#sidebar').hidden = false; $('#nav').hidden = false; $('.user-profile-badge').hidden = false;
  $('#menuBtn').hidden = false; $('#authAction').hidden = false;
  const allowed = new Set(rolePages[signedInUser.role] || []);
  document.querySelectorAll('#nav a').forEach(a => {
    const key = a.getAttribute('href').slice(1);
    a.hidden = !allowed.has(key);
    const labels = {
      dashboard: signedInUser.role === 'ORGANIZATION_MANAGER' ? 'Organization Dashboard' : signedInUser.role === 'ORGANIZATION_MEMBER' ? 'My Dashboard' : 'Command Center',
      organizations: signedInUser.role === 'ORGANIZATION_MANAGER' ? 'Organization Profile' : signedInUser.role === 'ORGANIZATION_MEMBER' ? 'Organization Information' : 'Organization Directory',
      requests: signedInUser.role === 'ORGANIZATION_MANAGER' ? 'Organization Requests' : signedInUser.role === 'ORGANIZATION_MEMBER' ? 'My Requests' : 'Requests Queue',
      allocations: signedInUser.role.startsWith('ORGANIZATION_') ? 'Organization Allocations' : 'Allocation Center',
      metrics: signedInUser.role === 'ORGANIZATION_MANAGER' ? 'Organization Metrics' : 'Performance Metrics'
    };
    const label = labels[key];
    if (label && a.lastChild?.nodeType === Node.TEXT_NODE) a.lastChild.textContent = ` ${label}`;
  });
  const badge = $('#authAction');
  if (badge) badge.textContent = 'Sign out';
  $('#dbBadge').hidden = false;
  startLiveEventStream();
}
function clearSession() {
  stopLiveEventStream();
  accessToken = null;
  signedInUser = null;
  authChecked = true;
  document.body.classList.add('auth-logged-out');
  $('#sidebar').hidden = true; $('#nav').hidden = true; $('.user-profile-badge').hidden = true;
  $('#menuBtn').hidden = true; $('#dbBadge').hidden = true; $('#authAction').hidden = true;
  $('#pageTitle').textContent = 'Sign in';
  document.querySelectorAll('#nav a').forEach(a => { a.hidden = true; a.classList.remove('active'); });
}
function brandMarkup(compact = false) {
  return `<span class="brand-mark" aria-hidden="true">⛑</span><span class="brand-wordmark"><b>DRAS<span> 2.0</span></b>${compact ? '' : '<small>Disaster Resource Allocation System</small>'}</span>`;
}

const publicBenefits = [
  'Intelligent resource allocation', 'Multi-organisation coordination',
  'Operational monitoring', 'Resource analytics'
];

function landingIntroSeen() {
  // Keep repeat hash renders in this document from replaying; a full page reload resets this flag.
  return landingIntroPlayed;
}

function markLandingIntroSeen() {
  landingIntroPlayed = true;
}

function playCinematicIntro(shell) {
  const intro = shell.querySelector('.landing-intro');
  const skip = intro?.querySelector('.skip-intro');
  const canvas = intro?.querySelector('.intro-particles');
  const logo = intro?.querySelector('.intro-brand');
  const mapImage = intro?.querySelector('.intro-map-image');
  const persistentCanvas = shell.querySelector('.landing-network-canvas');
  if (!intro || !skip || !canvas || !logo || !mapImage) {
    shell.classList.remove('intro-active');
    shell.classList.add('intro-complete');
    intro?.remove();
    markLandingIntroSeen();
    return () => {};
  }

  markLandingIntroSeen();
  const reducedMotion = window.matchMedia('(prefers-reduced-motion: reduce)').matches;
  const mobile = window.matchMedia('(max-width: 700px)').matches;
  const maxParticles = mobile ? 320 : 760;
  const sceneDuration = reducedMotion ? 900 : 6700;
  const mapContext = persistentCanvas?.getContext('2d');
  let context = canvas.getContext('2d');
  let animationFrame = 0;
  let finishTimer = 0;
  let disposed = false;
  let finished = false;
  let particles = [];
  let width = 0, height = 0, ratio = 1;

  const random = (min, max) => min + Math.random() * (max - min);
  const clamp = value => Math.max(0, Math.min(1, value));
  const smooth = value => { const t = clamp(value); return t * t * (3 - 2 * t); };
  const setCanvasSize = target => {
    if (!target) return null;
    target.width = Math.max(1, Math.round(width * ratio));
    target.height = Math.max(1, Math.round(height * ratio));
    const targetContext = target.getContext('2d');
    if (targetContext) targetContext.setTransform(ratio, 0, 0, ratio, 0, 0);
    return targetContext;
  };

  const resize = () => {
    width = Math.max(1, window.innerWidth);
    height = Math.max(1, window.innerHeight);
    ratio = Math.min(window.devicePixelRatio || 1, 1.5);
    context = setCanvasSize(canvas);
    if (persistentCanvas) {
      persistentCanvas.width = Math.max(1, Math.round(width * ratio));
      persistentCanvas.height = Math.max(1, Math.round(height * ratio));
      mapContext?.setTransform(ratio, 0, 0, ratio, 0, 0);
    }
    particles = makeLogoParticles();
  };

  const makeLogoParticles = () => {
    const mask = document.createElement('canvas');
    mask.width = 230; mask.height = 82;
    const maskContext = mask.getContext('2d', { willReadFrequently: true });
    if (!maskContext) return [];
    maskContext.textBaseline = 'alphabetic';
    maskContext.font = '36px Arial, sans-serif';
    maskContext.fillStyle = '#a9dff2';
    maskContext.fillText('⛑', 24, 57);
    maskContext.font = '700 31px "Space Grotesk", system-ui, sans-serif';
    maskContext.fillStyle = '#fffdf5';
    maskContext.fillText('DRAS', 84, 54);
    maskContext.font = '700 18px "Space Grotesk", system-ui, sans-serif';
    maskContext.fillStyle = '#87ceeb';
    maskContext.fillText('2.0', 161, 52);
    const pixels = maskContext.getImageData(0, 0, mask.width, mask.height).data;
    const samples = [];
    for (let y = 2; y < mask.height; y += 3) {
      for (let x = 2; x < mask.width; x += 3) {
        if (pixels[(y * mask.width + x) * 4 + 3] > 45) samples.push({ x, y });
      }
    }
    for (let i = samples.length - 1; i > 0; i--) {
      const j = Math.floor(Math.random() * (i + 1));
      [samples[i], samples[j]] = [samples[j], samples[i]];
    }
    const selected = samples.slice(0, maxParticles);
    const hubs = [[.19,.32],[.34,.48],[.55,.25],[.72,.35],[.82,.57],[.60,.72],[.30,.76]];
    return selected.map(point => {
      const hub = hubs[Math.floor(Math.random() * hubs.length)];
      const targetX = clamp(hub[0] + random(-.065, .065)) * width;
      const targetY = clamp(hub[1] + random(-.09, .09)) * height;
      return {
        sx: width / 2 + point.x - mask.width / 2,
        sy: height / 2 + point.y - mask.height / 2,
        tx: targetX,
        ty: targetY,
        curve: random(-75, 75),
        delay: random(0, .22),
        size: random(.65, 1.55),
        red: Math.random() < .075,
        phase: random(0, Math.PI * 2)
      };
    });
  };

  const drawSettledNetwork = () => {
    if (!mapContext) return;
    mapContext.clearRect(0, 0, width, height);
    particles.forEach(particle => {
      const color = particle.red ? '#e45b49' : (Math.random() < .18 ? '#ffd889' : '#f4c56e');
      mapContext.globalAlpha = particle.red ? .72 : .64;
      mapContext.fillStyle = color;
      mapContext.beginPath();
      mapContext.arc(particle.tx, particle.ty, particle.size, 0, Math.PI * 2);
      mapContext.fill();
      mapContext.globalAlpha = particle.red ? .1 : .12;
      mapContext.beginPath();
      mapContext.arc(particle.tx, particle.ty, particle.size * 3.2, 0, Math.PI * 2);
      mapContext.fill();
    });
    mapContext.globalAlpha = 1;
  };

  const cleanupListeners = () => {
    window.removeEventListener('resize', resize);
    skip.removeEventListener('click', onSkip);
  };
  const removeIntro = () => {
    if (finishTimer) window.clearTimeout(finishTimer);
    intro.remove();
    if (landingIntroCleanup === dispose) landingIntroCleanup = null;
  };
  const dispose = () => {
    if (disposed) return;
    disposed = true;
    if (animationFrame) cancelAnimationFrame(animationFrame);
    cleanupListeners();
    removeIntro();
  };
  const finish = skipped => {
    if (finished || disposed) return;
    finished = true;
    if (animationFrame) cancelAnimationFrame(animationFrame);
    cleanupListeners();
    if (!skipped) drawSettledNetwork();
    shell.classList.remove('intro-active');
    shell.classList.add('intro-complete');
    intro.classList.add(skipped ? 'intro-skipped' : 'intro-finished');
    if (skipped) removeIntro();
    else finishTimer = window.setTimeout(removeIntro, 720);
  };
  function onSkip() { finish(true); }

  resize();
  window.addEventListener('resize', resize, { passive: true });
  skip.addEventListener('click', onSkip);
  landingIntroCleanup = dispose;
  if (reducedMotion || !context || !mapContext) {
    intro.classList.add('reduced-intro');
    logo.style.opacity = '1';
    mapImage.style.opacity = '1';
    finishTimer = window.setTimeout(() => finish(false), reducedMotion ? 850 : 1150);
    return dispose;
  }

  const startTime = performance.now();
  const draw = now => {
    if (disposed || finished || !intro.isConnected) return;
    const elapsed = now - startTime;
    const scatterProgress = clamp((elapsed - 2200) / 2900);
    const blankMapFade = smooth(clamp((elapsed - 1200) / 1000));
    const mapReveal = smooth(clamp((elapsed - 2200) / 3700));
    mapImage.style.opacity = String(elapsed < 1200 ? 0 : elapsed < 2200 ? .16 * blankMapFade : .16 + .38 * mapReveal);
    mapImage.style.filter = `brightness(${.12 + .34 * mapReveal}) saturate(${.35 + .4 * mapReveal})`;
    logo.style.opacity = String(elapsed < 2200 ? 1 : 1 - smooth(scatterProgress * 1.12));
    context.clearRect(0, 0, width, height);
    if (elapsed >= 2200) {
      particles.forEach(particle => {
        const raw = clamp((scatterProgress - particle.delay) / (1 - particle.delay));
        const progress = smooth(raw);
        const midX = (particle.sx + particle.tx) / 2;
        const midY = (particle.sy + particle.ty) / 2 + particle.curve;
        const inv = 1 - progress;
        const x = inv * inv * particle.sx + 2 * inv * progress * midX + progress * progress * particle.tx;
        const y = inv * inv * particle.sy + 2 * inv * progress * midY + progress * progress * particle.ty;
        const arriving = clamp((scatterProgress - particle.delay) * 2.2);
        const twinkle = .82 + .18 * Math.sin(now / 410 + particle.phase);
        const color = particle.red ? '#df5143' : '#f2c56e';
        context.globalAlpha = (.28 + .62 * arriving) * twinkle;
        context.fillStyle = color;
        context.beginPath();
        context.arc(x, y, particle.size, 0, Math.PI * 2);
        context.fill();
        context.globalAlpha = .11 * arriving;
        context.beginPath();
        context.arc(x, y, particle.size * 3.5, 0, Math.PI * 2);
        context.fill();
      });
      context.globalAlpha = 1;
    }
    if (elapsed >= sceneDuration) { finish(false); return; }
    animationFrame = requestAnimationFrame(draw);
  };

  animationFrame = requestAnimationFrame(draw);
  return dispose;
}

async function pgLanding() {
  $('#pageTitle').textContent = 'Disaster Resource Allocation System';
  landingIntroCleanup?.();
  landingIntroCleanup = null;
  const intro = !landingIntroSeen();
  view().className = 'public-view landing-view';
  view().innerHTML = `<div class="landing-shell${intro ? ' intro-active' : ''}">
    <div class="landing-map" aria-hidden="true"><canvas class="landing-network-canvas"></canvas><span class="map-light light-one"></span><span class="map-light light-two"></span><span class="map-light light-three"></span><span class="map-light light-red"></span></div>
    ${intro ? `<div class="landing-intro" role="presentation"><div class="intro-map-layer" aria-hidden="true"><img class="intro-map-image" src="/images/night-network.jpg" alt=""></div><canvas class="intro-particles" aria-hidden="true"></canvas><div class="intro-brand" role="img" aria-label="DRAS 2.0">${brandMarkup(true)}</div><button class="skip-intro" type="button">Skip intro</button></div>` : ''}
    <nav class="public-nav" aria-label="Main navigation"><a class="public-brand" href="/" aria-label="DRAS home">${brandMarkup()}</a>
      <div class="public-nav-actions"><a class="text-link" href="/login">Sign in</a><a class="btn public-nav-cta" href="/dashboard">Access dashboard</a></div></nav>
    <section class="landing-content" aria-labelledby="landingTitle"><div class="landing-eyebrow"><span class="eyebrow-line"></span> COORDINATION FOR RESILIENT RESPONSE</div>
      <h1 id="landingTitle">Coordinating Resources.<br><span>Strengthening Disaster Response.</span></h1>
      <p class="landing-description">An intelligent disaster resource allocation platform designed to coordinate organisations, manage critical resources and support informed emergency-response decisions.</p>
      <div class="landing-actions"><a class="btn landing-primary" href="/dashboard">Access dashboard <span aria-hidden="true">→</span></a><a class="btn landing-secondary" href="/register">Request access</a></div>
      <div class="landing-feature-grid" aria-label="Platform capabilities">${[
        ['01', 'Intelligent Resource Allocation', 'Coordinate supplies with safe, data-driven decisions.'],
        ['02', 'Multi-Organisation Coordination', 'Connect organisations around a shared operational picture.'],
        ['03', 'Operational Monitoring', 'Track requests, allocations and changing availability.'],
        ['04', 'Resource Analytics', 'Turn live inventory and operational records into insight.']
      ].map(([number, title, description]) => `<article class="landing-feature"><span>${number}</span><h2>${title}</h2><p>${description}</p></article>`).join('')}</div>
    </section>
    <section class="landing-workflow" aria-labelledby="workflowTitle"><div class="workflow-heading"><span class="landing-eyebrow">A CLEAR OPERATIONAL FLOW</span><h2 id="workflowTitle">From request to response</h2></div><ol>${[
      'Organisations submit resource requests.', 'Dispatchers review and allocate available resources.', 'Organisations manage their allocated resources.'
    ].map((step, index) => `<li><span>0${index + 1}</span><p>${step}</p></li>`).join('')}</ol></section>
    <section class="landing-about"><div><span class="landing-eyebrow">ABOUT DRAS</span><h2>Built to make complex operations understandable.</h2></div><p>DRAS is an academic disaster-response coordination system. It demonstrates operating-system scheduling, safe resource allocation and database transaction management through a practical, multi-organisation workflow. It is a simulator and is not connected to real emergency services.</p></section>
    <footer class="landing-footer"><a class="public-brand" href="/">${brandMarkup(true)}</a><span>Academic disaster response simulator</span><a href="/login">Sign in to DRAS <span aria-hidden="true">→</span></a></footer>
  </div>`;
  if (intro) playCinematicIntro(view().querySelector('.landing-shell'));
  else view().querySelector('.landing-shell')?.classList.add('intro-complete');
}

async function pgAuth(initialMode = 'login') {
  $('#pageTitle').textContent = initialMode === 'register' ? 'Request access' : 'Sign in';
  $('#dbBadge').hidden = true;
  $('#sidebar').classList.remove('open');
  document.querySelectorAll('#nav a').forEach(a => a.classList.remove('active'));
  let organizations = [];
  let organizationLoad = 'idle';
  let organizationError = '';
  let organizationRequest = null;
  let requestedRole = 'ORGANIZATION_MEMBER';
  let organizationMatches = [];
  let matchTimer = null;
  view().className = 'public-view auth-view';
  view().innerHTML = `<div class="auth-shell auth-split"><section class="auth-form-panel"><a class="public-brand auth-brand" href="/" aria-label="DRAS home">${brandMarkup()}</a><div class="auth-form-content"><div class="eyebrow">DISASTER RESOURCE ALLOCATION SYSTEM</div>
    <h2 id="authHeading">Sign in to DRAS</h2><p class="muted" id="authHelp">Use your approved account to access operations.</p>
    <form id="authForm" class="auth-form"><div id="authFields"></div><p id="authError" class="form-feedback" role="alert"></p>
      <button class="btn" id="authSubmit" type="submit">Sign in</button></form>
    <p class="auth-switch-copy"><span id="authSwitchPrompt">New to DRAS?</span> <a href="/register" id="authSwitch">Create an account</a></p></div></section>
    <aside class="auth-brand-panel"><div class="auth-map" aria-hidden="true"><span class="map-light light-one"></span><span class="map-light light-two"></span><span class="map-light light-three"></span><span class="map-light light-red"></span></div>
      <div class="auth-brand-content"><a class="public-brand auth-brand-panel-logo" href="/">${brandMarkup()}</a><div class="auth-brand-copy"><span class="landing-eyebrow">ONE NETWORK. COORDINATED RESPONSE.</span><h2>When every resource counts, coordination matters.</h2><p>Connecting organisations, resources and emergency-response operations through intelligent allocation and transparent coordination.</p>
        <ul>${publicBenefits.map(item => `<li><span aria-hidden="true">↗</span>${item}</li>`).join('')}</ul></div><span class="auth-academic-note">An academic operations simulator</span></div></aside></div>`;
  let mode = initialMode;
  const draw = () => {
    const register = mode === 'register';
    $('#pageTitle').textContent = register ? 'Request access' : 'Sign in';
    $('#authHeading').textContent = register ? 'Request an account' : 'Sign in to DRAS';
    $('#authHelp').textContent = register ? 'New accounts require administrator approval.' : 'Use your approved account to access operations.';
    $('#authSwitch').textContent = register ? 'Sign in' : 'Create an account';
    $('#authSwitch').href = register ? '/login' : '/register';
    $('#authSwitchPrompt').textContent = register ? 'Already have an account?' : 'New to DRAS?';
    $('#authSubmit').textContent = register ? 'Submit registration' : 'Sign in';
    const selectionReady = requestedRole === 'ORGANIZATION_MEMBER'
      ? organizationLoad === 'ready' && !!$('#authOrg')?.value
      : requestedRole === 'ORGANIZATION_MANAGER'
        ? ($('#authRequestedOrg')?.value.trim().length ?? 0) >= 3
        : ['SYSTEM_ADMIN', 'DISPATCHER'].includes(requestedRole);
    $('#authSubmit').disabled = register && !selectionReady;
    $('#authFields').innerHTML = `${register ? `<label for="authName">Full name</label><input id="authName" autocomplete="name" required maxlength="120">
      <label for="authContact">Contact information <span class="muted">(optional)</span></label><input id="authContact" maxlength="200">
      <label for="authRequestedRole">Requested Role</label><select id="authRequestedRole" required>
        <option value="SYSTEM_ADMIN" ${requestedRole === 'SYSTEM_ADMIN' ? 'selected' : ''}>System Administrator</option>
        <option value="DISPATCHER" ${requestedRole === 'DISPATCHER' ? 'selected' : ''}>Dispatcher</option>
        <option value="ORGANIZATION_MANAGER" ${requestedRole === 'ORGANIZATION_MANAGER' ? 'selected' : ''}>Organization Manager</option>
        <option value="ORGANIZATION_MEMBER" ${requestedRole === 'ORGANIZATION_MEMBER' ? 'selected' : ''}>Organization Member</option>
      </select><div id="registrationOrganizationFields"></div>` : ''}
      <label for="authEmail">Email</label><input id="authEmail" type="email" autocomplete="username" required maxlength="160">
      <label for="authPassword">Password</label><input id="authPassword" type="password" autocomplete="${register ? 'new-password' : 'current-password'}" required ${register ? 'minlength="12"' : ''} maxlength="72">
      ${register ? '<label for="authConfirmPassword">Confirm Password</label><input id="authConfirmPassword" type="password" autocomplete="new-password" required minlength="12" maxlength="72">' : ''}`;
    if (register) {
      drawOrganizationFields();
      $('#authRequestedRole').onchange = () => {
        const fields = ['authName', 'authContact', 'authEmail', 'authPassword', 'authConfirmPassword'];
        const preserved = Object.fromEntries(fields.map(id => [id, document.getElementById(id)?.value ?? '']));
        const previousOrganization = $('#authOrg')?.value;
        const previousRequestedName = $('#authRequestedOrg')?.value ?? '';
        requestedRole = $('#authRequestedRole').value;
        organizationMatches = [];
        draw();
        fields.forEach(id => { const input = document.getElementById(id); if (input) input.value = preserved[id]; });
        if (requestedRole === 'ORGANIZATION_MANAGER') {
          $('#authRequestedOrg').value = previousRequestedName;
          if (previousRequestedName) $('#authRequestedOrg').dispatchEvent(new Event('input'));
        } else if (requestedRole === 'ORGANIZATION_MEMBER' && previousOrganization) {
          const selected = organizations.find(o => String(o.id) === previousOrganization);
          if (selected && $('#authOrg')) {
            $('#authOrg').value = selected.id;
            $('#authOrgSearch').value = `${selected.name} — ${selected.orgType}`;
            $('#authOrgSelection').textContent = `Selected: ${selected.name} — ${selected.orgType}`;
            $('#authSubmit').disabled = false;
          }
        }
        if (requestedRole === 'ORGANIZATION_MEMBER' && organizationLoad === 'idle') loadOrganizations();
      };
    }
  };
  const drawOrganizationFields = () => {
    const host = $('#registrationOrganizationFields');
    if (!host) return;
    if (requestedRole === 'SYSTEM_ADMIN' || requestedRole === 'DISPATCHER') {
      const roleLabel = requestedRole === 'SYSTEM_ADMIN' ? 'System Administrator' : 'Dispatcher';
      host.innerHTML = `<div class="organization-state" role="status">${roleLabel} access requires explicit approval by an existing System Administrator. This registration creates a pending privileged-role request only.</div>`;
      return;
    }
    if (requestedRole === 'ORGANIZATION_MANAGER') {
      host.innerHTML = `<label for="authRequestedOrg">Your Organization Name</label><input id="authRequestedOrg" autocomplete="organization" required minlength="3" maxlength="120" placeholder="Enter your organization name"><div id="organizationMatchFeedback" class="organization-state" role="status" aria-live="polite">Potential matches will appear as you type.</div>`;
      $('#authRequestedOrg').addEventListener('input', () => {
        $('#authSubmit').disabled = $('#authRequestedOrg').value.trim().length < 3;
        clearTimeout(matchTimer);
        const query = $('#authRequestedOrg').value.trim();
        if (query.length < 3) { organizationMatches = []; $('#organizationMatchFeedback').textContent = 'Enter at least 3 characters to check for existing organizations.'; return; }
        $('#organizationMatchFeedback').textContent = 'Checking for existing organizations…';
        matchTimer = setTimeout(async () => {
          try {
            organizationMatches = await GET('/api/auth/organization-matches?name=' + encodeURIComponent(query));
            const feedback = $('#organizationMatchFeedback');
            if (!feedback) return;
            feedback.className = 'organization-state' + (organizationMatches.length ? ' error' : '');
            feedback.textContent = organizationMatches.length
              ? `This organization may already be registered: ${organizationMatches.map(o => `${o.name} — ${o.orgType}`).join('; ')}. An administrator will review your request.`
              : 'No similar active organization was found. An administrator will review the new organization request.';
          } catch (error) {
            const feedback = $('#organizationMatchFeedback');
            if (feedback) { feedback.className = 'organization-state error'; feedback.textContent = `Could not check for similar organizations. ${error.message}`; }
          }
        }, 250);
      });
      return;
    }
    host.innerHTML = `${organizationLoad === 'loading' ? '<div class="organization-state loading" role="status"><span class="loading-indicator" aria-hidden="true"></span>Loading active organizations…</div>' : ''}
      ${organizationLoad === 'error' ? `<div class="organization-state error" role="alert">${esc(organizationError)}</div><button class="btn sec" id="authOrgRetry" type="button">Try again</button>` : ''}
      ${organizationLoad === 'empty' ? '<div class="organization-state" role="status">No active organizations are currently available for registration.</div>' : ''}
      ${organizationLoad === 'ready' ? `<label for="authOrgSearch">Select Your Organization</label><div class="organization-picker"><input id="authOrgSearch" type="search" role="combobox" aria-autocomplete="list" aria-controls="authOrgOptions" aria-expanded="false" autocomplete="off" placeholder="Search and choose an organization" required><input id="authOrg" type="hidden" value=""><div id="authOrgOptions" class="organization-options" role="listbox" hidden>${organizations.map(o => `<button type="button" role="option" aria-selected="false" data-organization-id="${esc(o.id)}">${esc(o.name)} <span>— ${esc(o.orgType)}</span></button>`).join('')}</div></div><p id="authOrgSelection" class="organization-selection" aria-live="polite">No organization selected.</p>` : ''}`;
    const search = $('#authOrgSearch'), options = $('#authOrgOptions');
    if (search && options) {
      const showOptions = visible => { options.hidden = !visible; search.setAttribute('aria-expanded', String(visible)); };
      const filterOptions = () => {
        const query = search.value.trim().toLocaleLowerCase();
        const matches = [...options.querySelectorAll('[role="option"]')].filter(option => {
          const visible = option.textContent.toLocaleLowerCase().includes(query); option.hidden = !visible; return visible;
        });
        showOptions(true);
        if (!matches.length) $('#authOrgSelection').textContent = 'No active organization matches that search.';
      };
      search.addEventListener('focus', filterOptions);
      search.addEventListener('input', () => {
        $('#authOrg').value = '';
        $('#authSubmit').disabled = true;
        $('#authOrgSelection').textContent = 'No organization selected.';
        filterOptions();
      });
      search.addEventListener('keydown', event => {
        if (event.key === 'Escape') showOptions(false);
        if (event.key === 'Enter' && !options.hidden) { event.preventDefault(); [...options.querySelectorAll('[role="option"]')].find(option => !option.hidden)?.click(); }
      });
      options.querySelectorAll('[role="option"]').forEach(option => option.addEventListener('click', () => {
        const selected = organizations.find(o => String(o.id) === option.dataset.organizationId);
        if (!selected) return;
        $('#authOrg').value = selected.id; search.value = `${selected.name} — ${selected.orgType}`;
        $('#authOrgSelection').textContent = `Selected: ${selected.name} — ${selected.orgType}`;
        options.querySelectorAll('[role="option"]').forEach(item => item.setAttribute('aria-selected', String(item === option)));
        showOptions(false); $('#authSubmit').disabled = false;
      }));
    }
    $('#authOrgRetry')?.addEventListener('click', loadOrganizations);
  };
  const loadOrganizations = async () => {
    if (organizationRequest) return organizationRequest;
    organizationLoad = 'loading'; organizationError = ''; draw();
    organizationRequest = GET('/api/auth/organizations').then(data => {
      if (!Array.isArray(data)) throw new Error('The organization service returned an unexpected response.');
      organizations = data.filter(o => o && o.active !== false && Number.isInteger(Number(o.id)) && o.name && o.orgType);
      organizationLoad = organizations.length ? 'ready' : 'empty';
    }).catch(error => {
      organizationLoad = 'error';
      organizationError = `Unable to load organizations. ${error.message || 'Check your connection and try again.'}`;
    }).finally(() => { organizationRequest = null; draw(); });
    return organizationRequest;
  };
  draw();
  if (mode === 'register') loadOrganizations();
  $('#authSwitch').onclick = event => {
    event.preventDefault();
    mode = mode === 'login' ? 'register' : 'login';
    setPublicPath(mode === 'register' ? '/register' : '/login');
    requestedRole = 'ORGANIZATION_MEMBER'; organizationMatches = [];
    if (mode === 'register') organizationLoad = 'idle';
    draw();
    if (mode === 'register' && organizationLoad === 'idle') loadOrganizations();
  };
  $('#authForm').onsubmit = async event => {
    event.preventDefault();
    const button = $('#authSubmit');
    const error = $('#authError');
    button.disabled = true; button.classList.add('loading'); error.textContent = '';
    try {
      const credentials = { email: val('authEmail'), password: val('authPassword') };
      if (mode === 'register') {
        if (val('authPassword') !== val('authConfirmPassword')) throw new Error('Password and confirmation do not match.');
        const organizationId = requestedRole === 'ORGANIZATION_MEMBER' ? Number($('#authOrg')?.value) : null;
        const requestedOrganizationName = requestedRole === 'ORGANIZATION_MANAGER' ? val('authRequestedOrg').trim() : null;
        if (requestedRole === 'ORGANIZATION_MEMBER' && (organizationLoad !== 'ready' || !Number.isInteger(organizationId) || organizationId < 1)) {
          $('#authOrgSearch')?.focus();
          throw new Error('Select an active organization before submitting registration.');
        }
        await POST('/api/auth/register', { ...credentials, confirmPassword: val('authConfirmPassword'),
          fullName: val('authName'), contactInfo: val('authContact') || null, requestedRole, organizationId, requestedOrganizationName });
        error.className = 'form-feedback success';
        error.textContent = 'Your account registration has been submitted successfully. It is awaiting administrator approval.';
        mode = 'login'; setPublicPath('/login'); draw();
      } else {
        setSession(await POST('/api/auth/login', credentials));
        history.replaceState({}, '', '/dashboard#dashboard');
        await render();
      }
    } catch (e) { error.className = 'form-feedback'; error.textContent = e.message; }
    finally { button.disabled = false; button.classList.remove('loading'); }
  };
}
async function pgPasswordChange() {
  $('#pageTitle').textContent = 'Change temporary password';
  view().innerHTML = `<div class="auth-shell"><section class="panel auth-panel"><div class="eyebrow">ACCOUNT SECURITY</div>
    <h2>Change your temporary password</h2><p class="muted">You must set a new password before accessing DRAS.</p>
    <form id="passwordChangeForm" class="auth-form">
      <label for="currentPassword">Temporary password</label><input id="currentPassword" type="password" autocomplete="current-password" required maxlength="72">
      <label for="newPassword">New password</label><input id="newPassword" type="password" autocomplete="new-password" required minlength="12" maxlength="72">
      <label for="confirmNewPassword">Confirm new password</label><input id="confirmNewPassword" type="password" autocomplete="new-password" required minlength="12" maxlength="72">
      <p id="passwordChangeFeedback" class="form-feedback" role="alert"></p>
      <button class="btn" id="passwordChangeSubmit" type="submit">Update password</button>
    </form></section></div>`;
  $('#passwordChangeForm').onsubmit = async event => {
    event.preventDefault();
    const button = $('#passwordChangeSubmit'), feedback = $('#passwordChangeFeedback');
    button.disabled = true; button.classList.add('loading'); feedback.textContent = '';
    try {
      const session = await POST('/api/auth/change-password', {
        currentPassword: val('currentPassword'), newPassword: val('newPassword'), confirmPassword: val('confirmNewPassword')
      });
      setSession(session);
      toast('Password updated. You can now access DRAS.', 'ok');
      await render();
    } catch (error) {
      feedback.textContent = error.message;
      button.disabled = false;
    } finally { button.classList.remove('loading'); }
  };
}
async function pingHealth() {
  const b = $('#dbBadge');
  try {
    const h = await GET('/api/health');
    const counts = Number.isFinite(h.organizations) && Number.isFinite(h.resources)
      ? ` · ${h.organizations} Orgs · ${h.resources} Resources` : '';
    b.innerHTML = `<span class="status-dot success"></span> DB Active${counts}`;
    b.className = 'badge ok';
  } catch {
    b.innerHTML = `<span class="status-dot critical"></span> Database Unreachable`;
    b.className = 'badge bad';
  }
}
window.addEventListener('hashchange', render);
window.addEventListener('popstate', render);
$('#menuBtn').addEventListener('click', () => $('#sidebar').classList.toggle('open'));
$('#authAction').addEventListener('click', async () => {
  if (!accessToken) { await pgAuth(); return; }
  try { await api('POST', '/api/auth/logout', {}); } catch { /* Local session is cleared even if network logout fails. */ }
  clearSession();
  await render();
});

// ---------- Analytics visualizations (native SVG; values come from the analytics API) ----------
const ANALYTICS_COLORS = ['#4AADD6', '#60B98B', '#F0A443', '#7A78D1', '#58B7AE', '#E07E7E', '#7892A4'];
const STATUS_COLORS = { PENDING: '#F0A443', WAITING: '#D98B36', PROCESSING: '#4AADD6', PARTIALLY_ALLOCATED: '#5D9CC7', ALLOCATED: '#60B98B', COMPLETED: '#6A9E82', REJECTED: '#EF5350' };
const analyticsNumber = value => Number.isFinite(Number(value)) ? Number(value) : 0;
function donutChart(rows, labelKey, valueKey, colors = ANALYTICS_COLORS) {
  const usable = rows.map((row, i) => ({ label: row[labelKey], value: analyticsNumber(row[valueKey]), color: colors[i % colors.length] })).filter(row => row.value > 0);
  const total = usable.reduce((sum, row) => sum + row.value, 0);
  const radius = 42, circumference = 2 * Math.PI * radius;
  let offset = 0;
  const arcs = usable.map(row => {
    const length = total ? row.value / total * circumference : 0;
    const arc = `<circle cx="60" cy="60" r="${radius}" fill="none" stroke="${row.color}" stroke-width="15" stroke-dasharray="${length} ${circumference - length}" stroke-dashoffset="${-offset}" transform="rotate(-90 60 60)"/>`;
    offset += length;
    return arc;
  }).join('');
  const legend = rows.length ? `<ul class="analytics-legend">${rows.map((row, i) => {
    const value = analyticsNumber(row[valueKey]);
    const percent = total ? Math.round(value / total * 100) : 0;
    return `<li><span class="analytics-legend-key" style="--chart-color:${colors[i % colors.length]}"></span><span>${esc(row[labelKey])}</span><b>${value.toLocaleString()} · ${percent}%</b></li>`;
  }).join('')}</ul>` : '<div class="empty">No records in this dataset.</div>';
  return `<div class="analytics-donut-wrap"><svg class="analytics-donut" viewBox="0 0 120 120" role="img" aria-label="${total ? `Distribution, ${total} total` : 'No distribution data'}"><circle cx="60" cy="60" r="42" fill="none" stroke="var(--border)" stroke-width="15"/>${arcs}<text x="60" y="57" text-anchor="middle" class="analytics-donut-total">${total.toLocaleString()}</text><text x="60" y="70" text-anchor="middle" class="analytics-donut-caption">TOTAL</text></svg>${legend}</div>`;
}
function resourceBars(categories) {
  if (!categories.length) return '<div class="empty">No active resource records.</div>';
  const width = 660, left = 145, right = 24, top = 14, row = 38;
  const max = Math.max(1, ...categories.flatMap(c => [analyticsNumber(c.availableUnits), analyticsNumber(c.allocatedUnits)]));
  const chartWidth = width - left - right;
  const bars = categories.map((c, i) => {
    const y = top + i * row;
    const available = analyticsNumber(c.availableUnits), allocated = analyticsNumber(c.allocatedUnits);
    const aw = available / max * chartWidth, lw = allocated / max * chartWidth;
    return `<text x="${left - 9}" y="${y + 12}" text-anchor="end" class="analytics-axis-label">${esc(c.category)}</text><rect x="${left}" y="${y}" width="${aw}" height="10" rx="4" fill="#4AADD6"/><text x="${Math.min(width - 4, left + aw + 5)}" y="${y + 9}" class="analytics-value-label">${available}</text><rect x="${left}" y="${y + 16}" width="${lw}" height="10" rx="4" fill="#60B98B"/><text x="${Math.min(width - 4, left + lw + 5)}" y="${y + 25}" class="analytics-value-label">${allocated}</text>`;
  }).join('');
  return `<div class="analytics-chart-scroll"><svg class="analytics-bars" viewBox="0 0 ${width} ${top + categories.length * row}" role="img" aria-label="Available and allocated units by resource category">${bars}</svg></div><div class="analytics-chart-key"><span><i style="--chart-color:#4AADD6"></i>Available</span><span><i style="--chart-color:#60B98B"></i>Allocated</span></div>`;
}
function allocationLine(trends) {
  if (!trends.length) return '<div class="empty">No allocation or release events in this period.</div>';
  const width = 660, height = 220, pad = { left: 38, right: 16, top: 16, bottom: 38 };
  const plotW = width - pad.left - pad.right, plotH = height - pad.top - pad.bottom;
  const max = Math.max(1, ...trends.flatMap(d => [analyticsNumber(d.allocatedUnits), analyticsNumber(d.releasedUnits)]));
  const x = i => pad.left + (trends.length === 1 ? plotW / 2 : i * plotW / (trends.length - 1));
  const y = value => pad.top + plotH - analyticsNumber(value) / max * plotH;
  const series = (key, color, title) => {
    const points = trends.map((d, i) => `${x(i)},${y(d[key])}`).join(' ');
    const dots = trends.map((d, i) => `<circle cx="${x(i)}" cy="${y(d[key])}" r="3.5" fill="${color}"><title>${esc(d.date)} ${title}: ${analyticsNumber(d[key])} units</title></circle>`).join('');
    return `<polyline points="${points}" fill="none" stroke="${color}" stroke-width="3" stroke-linecap="round" stroke-linejoin="round"/>${dots}`;
  };
  const labels = trends.filter((_, i) => i === 0 || i === trends.length - 1 || i % Math.max(1, Math.ceil(trends.length / 5)) === 0)
    .map(d => { const i = trends.indexOf(d); return `<text x="${x(i)}" y="${height - 10}" text-anchor="middle" class="analytics-axis-label">${esc(d.date.slice(5))}</text>`; }).join('');
  return `<div class="analytics-chart-scroll"><svg class="analytics-line" viewBox="0 0 ${width} ${height}" role="img" aria-label="Daily allocated and released quantities from historical allocation records"><line x1="${pad.left}" x2="${width - pad.right}" y1="${pad.top + plotH}" y2="${pad.top + plotH}" stroke="var(--border)"/>${series('allocatedUnits', '#4AADD6', 'allocated')}${series('releasedUnits', '#60B98B', 'released')}${labels}</svg></div><div class="analytics-chart-key"><span><i style="--chart-color:#4AADD6"></i>Allocated</span><span><i style="--chart-color:#60B98B"></i>Released</span></div>`;
}
function analyticsMarkup(data) {
  const r = data.resources, q = data.requests;
  const statusRows = data.requestStatuses.map(row => ({ ...row, color: STATUS_COLORS[row.status] || '#7892A4' }));
  const statCard = (value, label) => `<div class="analytics-stat"><strong>${analyticsNumber(value).toLocaleString()}</strong><span>${esc(label)}</span></div>`;
  return `<section class="analytics-section" aria-labelledby="analyticsHeading"><div class="analytics-heading"><div><div class="eyebrow">LIVE POSTGRESQL AGGREGATES</div><h2 id="analyticsHeading">Resource &amp; Request Analytics</h2></div><label class="analytics-range">Trend period <select id="analyticsDays"><option value="7" ${data.trendDays === 7 ? 'selected' : ''}>7 days</option><option value="30" ${data.trendDays === 30 ? 'selected' : ''}>30 days</option><option value="90" ${data.trendDays === 90 ? 'selected' : ''}>90 days</option><option value="180" ${data.trendDays === 180 ? 'selected' : ''}>180 days</option><option value="365" ${data.trendDays === 365 ? 'selected' : ''}>365 days</option></select><span id="analyticsRangeStatus" role="status" aria-live="polite"></span></label></div>
    ${r ? `<div class="analytics-summary-grid">${statCard(r.resourceCount, 'Active resources')}${statCard(r.totalUnits, 'Total units')}${statCard(r.availableUnits, 'Available')}${statCard(r.allocatedUnits, 'Allocated')}${statCard(`${analyticsNumber(r.utilizationPercentage).toFixed(2)}%`, 'Utilization')}${statCard(r.shortageCount, 'Shortage resources')}</div>` : `<p class="analytics-scope-note">${data.scope === 'REQUESTER' ? 'Request and allocation analytics are limited to your own requests.' : 'Request and allocation analytics are limited to your organization.'} System-wide inventory totals are restricted to dispatch roles.</p>`}
    <div class="analytics-chart-grid">${r ? `<article class="panel analytics-panel"><h3>Resource Distribution</h3><p class="muted">Active inventory units by database category</p>${donutChart(data.resourceCategories, 'category', 'totalUnits')}</article><article class="panel analytics-panel"><h3>Available vs Allocated</h3><p class="muted">Current active stock by category</p>${resourceBars(data.resourceCategories)}</article>` : ''}
      <article class="panel analytics-panel"><h3>Request Status</h3><p class="muted">Counts by existing workflow status</p>${donutChart(statusRows, 'status', 'count', statusRows.map(row => row.color))}</article><article class="panel analytics-panel"><h3>Request Demand by Category</h3><p class="muted">Requested and allocated units in your permitted scope</p>${requestBars(data.requestCategories)}</article>
      <article class="panel analytics-panel analytics-trend-panel"><h3>Allocation Trends</h3><p class="muted">Historical allocation and release quantities by event date</p>${allocationLine(data.allocationTrends)}</article></div>
    <div class="analytics-request-summary">${statCard(q.total, 'Total requests')}${statCard(q.pending, 'Pending / waiting')}${statCard(q.allocated, 'Allocated')}${statCard(q.processing, 'Being processed')}${statCard(q.rejected, 'Rejected')}${statCard(q.completed, 'Completed')}${statCard(q.critical, 'Critical (priority 5)')}</div></section>`;
}
function requestBars(categories) {
  if (!categories.length) return '<div class="empty">No requests in this dataset.</div>';
  return resourceBars(categories.map(c => ({ category: c.category, availableUnits: c.requestedUnits, allocatedUnits: c.allocatedUnits })));
}

// ---------- 1. Command Center (Layout A: Wide Photographic Header) ----------
async function pgDashboard() {
  view().innerHTML = '<div class="panel analytics-loading" role="status">Loading command center and analytics from the database…</div>';
  const [s, analytics] = await Promise.all([GET('/api/dashboard/stats'), GET('/api/analytics/dashboard')]);
  const canDispatch = ['SYSTEM_ADMIN', 'DISPATCHER'].includes(signedInUser?.role);
  const tenant = signedInUser?.role?.startsWith('ORGANIZATION_');
  const manager = signedInUser?.role === 'ORGANIZATION_MANAGER';
  const alerts = canDispatch ? await GET('/api/alerts') : [];
  const card = (n, l) => `<div class="card"><div class="n">${esc(n)}</div><div class="l">${esc(l)}</div></div>`;
  view().innerHTML = `
    <div class="section-hero layout-a hero-command-center">
      <div class="section-hero-bg"></div>
      <div class="section-hero-content">
        <div class="section-hero-title">Emergency Operations Center</div>
        <div class="section-hero-desc">${canDispatch ? 'Global command overview of disaster logistics, live resource allocation states, registered relief agencies, and queue metrics.' : manager ? 'Organization operations overview with your requests, allocations, and current resource availability.' : 'Your organization requests, request history, and relevant resource availability.'}</div>
        <div class="section-hero-meta">
          <span class="badge ok"><span class="status-dot success"></span> Systems Operational</span>
          <span class="badge"><span class="status-dot info"></span> ${s.activeAllocations} Active Deployments</span>
          ${s.pendingRequests > 0 ? `<span class="badge bad"><span class="status-dot critical"></span> ${s.pendingRequests} Pending Demands</span>` : '<span class="badge ok">Queue Clear</span>'}
        </div>
      </div>
    </div>
    ${canDispatch ? `<div class="panel alert-center" style="margin-top:20px"><h2>Open Operational Alerts</h2>${alerts.length ? alerts.map(a =>
      `<article class="alert-row ${String(a.level).toLowerCase()}"><div><b>${esc(a.title)}</b><p>${esc(a.message)}</p><small>${esc(a.level)} · ${esc(a.source)} · ${fmtTime(a.createdAt)}</small></div>`
      + (a.acknowledgedAt ? `<span class="pill info">Acknowledged ${esc(fmtTime(a.acknowledgedAt))}</span>` : `<button class="btn sm sec" data-ack="${a.id}">Acknowledge</button>`) + '</article>'
    ).join('') : '<p class="muted">No open resource alerts.</p>'}</div>` : ''}
    <div class="grid cards">
      ${tenant ? '' : `${card(s.totalUnits, 'Total Inventory Units')}${card(s.availableUnits, 'Available Reserves')}${card(s.allocatedUnits, 'Active Dispatched')}${card(s.organizations, 'Registered Organizations')}`}
      ${card(s.pendingRequests, tenant && !manager ? 'My Pending Requests' : 'Pending In Queue')}${card(s.completedAllocations, tenant && !manager ? 'My Allocations' : 'Allocations Logged')}
      ${card(s.activeAllocations, tenant && !manager ? 'My Active Allocations' : 'Active Deployments')}
    </div>
    <div id="dashboardAnalytics">${analyticsMarkup(analytics)}</div>
    <div class="grid two" style="margin-top:20px">
      <div class="panel"><h2>${tenant ? 'Relevant Resource Availability' : 'Resource Utilisation'}</h2>${table([
        { h: 'Resource', v: r => r.name }, { h: 'Category', v: r => r.category }, { h: 'Type', html: r => `<span class="pill ${r.reusable ? 'info' : ''}">${r.reusable ? 'Reusable' : 'Consumable'}</span>` },
        ...(tenant ? [{ h: 'Available', num: 1, v: r => r.availableQuantity }] : [
          { h: 'Total', num: 1, v: r => r.totalQuantity }, { h: 'Available', num: 1, v: r => r.availableQuantity }, { h: 'Allocated', num: 1, v: r => r.allocatedQuantity },
          { h: 'Capacity', html: r => `<div class="bar"><i style="width:${r.totalQuantity ? Math.min(100, Math.round(100 * r.allocatedQuantity / r.totalQuantity)) : 0}%"></i></div>` }
        ])
      ], s.resources, 'No active resource availability is listed')}</div>
      <div class="panel"><h2>Requests by Status</h2>${table([{ h: 'Status', html: r => pill(r.status) }, { h: 'Count', num: 1, v: r => r.count }], s.requestsByStatus, 'No requests logged')}
        <h2 style="margin-top:20px">Quick Operational Actions</h2>
        <div class="toolbar"><a class="btn" href="#requests">Submit Request</a>
          ${canDispatch ? '<a class="btn sec" href="#allocations">Process Queue</a><a class="btn sec" href="#bankers">Banker\'s Check</a>' : ''}</div>
      </div>
    </div>
    <div class="panel" style="margin-top:20px"><h2>${tenant && !manager ? 'My Request Activity' : tenant ? 'Organization Request Activity' : 'Recent Operational Activity (Resource Audit History)'}</h2>${table([
      { h: 'Timestamp', v: r => fmtTime(r.createdAt) }, { h: 'Resource', v: r => r.resourceName }, { h: 'Action', v: r => r.action },
      { h: 'Delta', num: 1, v: r => r.quantityChange }, { h: 'Inventory Transition', v: r => `${r.availableBefore} → ${r.availableAfter}` }, { h: 'Audit Note', v: r => r.note }
    ], s.recentActivity, 'No audit records logged yet')}</div>`;
  view().querySelectorAll('[data-ack]').forEach(button => button.onclick = async () => {
    button.disabled = true;
    await act(() => POST(`/api/alerts/${button.dataset.ack}/acknowledge`), 'Alert acknowledged');
  });
  const loadAnalyticsRange = async event => {
    const selector = event.currentTarget;
    selector.disabled = true;
    selector.closest('.analytics-section').setAttribute('aria-busy', 'true');
    $('#analyticsRangeStatus').textContent = 'Refreshing…';
    try {
      const refreshed = await GET(`/api/analytics/dashboard?days=${encodeURIComponent(selector.value)}`);
      $('#dashboardAnalytics').innerHTML = analyticsMarkup(refreshed);
      $('#analyticsDays').addEventListener('change', loadAnalyticsRange);
    } catch (e) {
      toast(`Analytics refresh failed: ${e.message}`, 'err');
      selector.disabled = false;
      selector.closest('.analytics-section').setAttribute('aria-busy', 'false');
      $('#analyticsRangeStatus').textContent = 'Refresh failed. The last results are still shown.';
    }
  };
  $('#analyticsDays')?.addEventListener('change', loadAnalyticsRange);
}

// ---------- 2. Resource Inventory (Layout B: Split Composition) ----------
const CATS = ['AMBULANCE', 'MEDICINE', 'FOOD', 'WATER', 'HOSPITAL_BED', 'MEDICAL_STAFF'];
async function pgInventory() {
  const canEdit = ['SYSTEM_ADMIN', 'DISPATCHER'].includes(signedInUser?.role);
  const tenant = signedInUser?.role?.startsWith('ORGANIZATION_');
  const list = await GET('/api/resources');
  view().innerHTML = `
    <div class="section-hero layout-b hero-resource-inventory">
      <div class="section-hero-content">
        <div class="section-hero-title">Resource Inventory</div>
        <div class="section-hero-desc">Logistics monitoring of physical disaster relief reserves, warehouses, emergency vehicles, and clinical personnel.</div>
        <div class="section-hero-meta">
          ${canEdit ? '<button class="btn" id="addRes">+ Add Resource</button>' : ''}
        </div>
      </div>
      <div class="section-hero-aside"><div class="section-hero-bg"></div></div>
    </div>
    <div class="panel">
      <div class="toolbar"><h2 style="margin:0;flex:1">Inventory Records</h2></div>
      ${filterBar('rf', 'Search resource name or location…', [{ key: 'cat', label: 'All categories', options: CATS }])}
      <div id="resTable"></div>
    </div>
    ${canEdit ? `<div class="panel" style="margin-top:20px">
      <h2>Resource History Audit Trail</h2>
      <div class="toolbar">
        <select id="histRes"><option value="">All resources</option>${opts(list, 'id', 'name')}</select>
      </div>
      <div id="histTable"></div>
    </div>` : ''}`;
  const draw = (q = '', f = {}) => {
    const rows = list.filter(r => (!q || (r.name + (r.location || '')).toLowerCase().includes(q)) && (!f.cat || r.category === f.cat));
    $('#resTable').innerHTML = table([
      ...(tenant ? [] : [{ h: 'ID', v: r => r.id }]), { h: 'Resource Name', v: r => r.name }, { h: 'Category', v: r => r.category },
      ...(tenant ? [{ h: 'Available', num: 1, v: r => r.availableQuantity }] : [
        { h: 'Location', v: r => r.location }, { h: 'Total', num: 1, v: r => r.totalQuantity },
        { h: 'Available', num: 1, v: r => r.availableQuantity }, { h: 'Allocated', num: 1, v: r => r.allocatedQuantity }
      ]),
      { h: 'Kind', html: r => `<span class="pill ${r.reusable ? 'info' : ''}">${r.reusable ? 'Reusable' : 'Consumable'}</span>` },
      ...(tenant ? [] : [{ h: 'Status', html: r => r.active ? '<span class="pill ok">Active</span>' : '<span class="pill">Inactive</span>' }]),
      ...(canEdit ? [{ h: 'Actions', html: r => `<button class="btn sm sec" data-edit="${r.id}">Edit</button> <button class="btn sm danger" data-del="${r.id}">Delete</button>` }] : [])
    ], rows, 'No matching resource found');
    $('#resTable').querySelectorAll('[data-edit]').forEach(b => b.onclick = () => resourceForm(list.find(r => r.id == b.dataset.edit)));
    $('#resTable').querySelectorAll('[data-del]').forEach(b => b.onclick = () => { if (confirm('Delete this resource?')) act(() => DEL('/api/resources/' + b.dataset.del), 'Resource deleted'); });
  };
  draw(); wireFilter('rf', [{ key: 'cat' }], draw);
  if (canEdit) $('#addRes').onclick = () => resourceForm(null);
  const hist = async () => {
    const id = val('histRes');
    const h = await GET('/api/resources/history?limit=30' + (id ? '&resourceId=' + id : ''));
    $('#histTable').innerHTML = table([
      { h: 'Timestamp', v: r => fmtTime(r.createdAt) }, { h: 'Resource', v: r => r.resourceName }, { h: 'Action', v: r => r.action },
      { h: 'Delta', num: 1, v: r => r.quantityChange }, { h: 'Balance', v: r => `${r.availableBefore} → ${r.availableAfter}` },
      { h: 'Request ID', v: r => r.requestId ? '#' + r.requestId : '—' }, { h: 'Audit Note', v: r => r.note }
    ], h, 'No historical events logged');
  };
  if (canEdit) { $('#histRes').onchange = hist; hist(); }
}
function resourceForm(r) {
  openModal(`<h2>${r ? 'Edit' : 'Add'} Resource</h2>
    <label>Resource Name</label><input id="f_name" value="${esc(r?.name)}" style="width:100%">
    <label>Category</label><select id="f_cat" style="width:100%">${CATS.map(c => `<option ${r?.category === c ? 'selected' : ''}>${c}</option>`).join('')}</select>
    <label>Total Quantity${r ? ` (currently ${r.allocatedQuantity} allocated)` : ''}</label><input id="f_total" type="number" min="0" value="${r?.totalQuantity ?? 0}" style="width:100%">
    <label>Location / Depot</label><input id="f_loc" value="${esc(r?.location)}" style="width:100%">
    <label style="margin-top:14px"><input type="checkbox" id="f_reuse" ${(r?.reusable ?? true) ? 'checked' : ''}> Reusable Asset (Units return upon release; subject to Banker's safety check)</label>
    <label><input type="checkbox" id="f_active" ${(r?.active ?? true) ? 'checked' : ''}> Active</label>
    <div class="toolbar" style="margin-top:20px"><button class="btn" id="f_save">Save Changes</button><button class="btn sec" onclick="closeModal()">Cancel</button></div>`);
  $('#f_save').onclick = () => {
    const body = { name: val('f_name').trim(), category: val('f_cat'), totalQuantity: num('f_total'), location: val('f_loc').trim(), reusable: $('#f_reuse').checked, active: $('#f_active').checked };
    act(() => r ? PUT('/api/resources/' + r.id, body) : POST('/api/resources', body), r ? 'Resource updated' : 'Resource created').then(x => { if (x) closeModal(); });
  };
}

// ---------- 3. Organization Directory (Layout B: Split Composition) ----------
const TYPES = ['HOSPITAL', 'SHELTER', 'RESCUE_TEAM'];
async function pgOrganizations() {
  const isAdmin = signedInUser?.role === 'SYSTEM_ADMIN';
  const isManager = signedInUser?.role === 'ORGANIZATION_MANAGER';
  const canManage = isAdmin || isManager;
  const showContacts = isAdmin || isManager;
  const list = await GET('/api/organizations');
  view().innerHTML = `
    <div class="section-hero layout-b hero-organization-directory">
      <div class="section-hero-content">
        <div class="section-hero-title">Organization Directory</div>
        <div class="section-hero-desc">Directory of participating hospitals, community shelters, and emergency rescue units with declared maximum resource claims.</div>
        <div class="section-hero-meta">
          ${isAdmin ? '<button class="btn" id="addOrg">+ Register Organization</button>' : ''}
        </div>
      </div>
      <div class="section-hero-aside"><div class="section-hero-bg"></div></div>
    </div>
    <div class="panel">
      <div class="toolbar"><h2 style="margin:0;flex:1">Registered Organizations</h2></div>
      ${filterBar('of', 'Search name or location…', [{ key: 'type', label: 'All types', options: TYPES }])}
      <div id="orgTable"></div>
    </div>`;
  const draw = (q = '', f = {}) => {
    const rows = list.filter(o => (!q || (o.name + (o.location || '')).toLowerCase().includes(q)) && (!f.type || o.orgType === f.type));
    $('#orgTable').innerHTML = table([
      { h: 'ID', v: o => o.id }, { h: 'Organization Name', v: o => o.name }, { h: 'Type', html: o => `<span class="pill info">${esc(o.orgType)}</span>` },
      { h: 'Location', v: o => o.location || '—' }, ...(showContacts ? [{ h: 'Contact Info', v: o => o.contactInfo || '—' }] : []),
      { h: 'Status', html: o => o.active ? '<span class="pill ok">Active</span>' : '<span class="pill">Inactive</span>' },
      { h: 'Actions', html: o => `${canManage ? `<button class="btn sm" data-claims="${o.id}">Resource Claims</button> <button class="btn sm sec" data-edit="${o.id}">Edit Profile</button>` : ''} ${isAdmin ? `<button class="btn sm danger" data-del="${o.id}">Delete</button>` : ''}` }
    ], rows, 'No matching organization found');
    const t = $('#orgTable');
    t.querySelectorAll('[data-edit]').forEach(b => b.onclick = () => orgForm(list.find(o => o.id == b.dataset.edit)));
    t.querySelectorAll('[data-claims]').forEach(b => b.onclick = () => claimsForm(list.find(o => o.id == b.dataset.claims)));
    t.querySelectorAll('[data-del]').forEach(b => b.onclick = () => { if (confirm('Delete this organization?')) act(() => DEL('/api/organizations/' + b.dataset.del), 'Organization deleted'); });
  };
  draw(); wireFilter('of', [{ key: 'type' }], draw); if (isAdmin) $('#addOrg').onclick = () => orgForm(null);
}
function orgForm(o) {
  const canSetActive = signedInUser?.role === 'SYSTEM_ADMIN';
  openModal(`<h2>${o ? 'Edit' : 'Register'} Organization</h2>
    <label>Organization Name</label><input id="f_name" value="${esc(o?.name)}" style="width:100%">
    <label>Organization Type</label><select id="f_type" style="width:100%">${TYPES.map(t => `<option ${o?.orgType === t ? 'selected' : ''}>${t}</option>`).join('')}</select>
    <label>Location / Jurisdiction</label><input id="f_loc" value="${esc(o?.location)}" style="width:100%">
    <label>Contact Info</label><input id="f_contact" value="${esc(o?.contactInfo)}" style="width:100%">
    ${canSetActive ? `<label style="margin-top:14px"><input type="checkbox" id="f_active" ${(o?.active ?? true) ? 'checked' : ''}> Active Organization</label>` : ''}
    <div class="toolbar" style="margin-top:20px"><button class="btn" id="f_save">Save Changes</button><button class="btn sec" onclick="closeModal()">Cancel</button></div>`);
  $('#f_save').onclick = () => {
    const body = { name: val('f_name').trim(), orgType: val('f_type'), location: val('f_loc').trim(), contactInfo: val('f_contact').trim() || null,
      active: canSetActive ? $('#f_active').checked : null };
    act(() => o ? PUT('/api/organizations/' + o.id, body) : POST('/api/organizations', body), o ? 'Organization updated' : 'Organization registered').then(x => { if (x) closeModal(); });
  };
}
async function claimsForm(o) {
  const [resources, claims] = await Promise.all([GET('/api/resources'), GET(`/api/organizations/${o.id}/claims`)]);
  const cur = Object.fromEntries(claims.map(c => [c.resourceId, c.maxClaim]));
  openModal(`<h2>Declared Maximum Claims — ${esc(o.name)}</h2>
    <p class="muted">Configures the Banker's Algorithm Maximum Matrix (<span class="mono">Max</span>). Requests exceeding declared limits are automatically rejected.</p>
    <div class="tablewrap"><table><thead><tr><th>Resource Name</th><th>Maximum Declared Units</th><th>Action</th></tr></thead><tbody>${resources.map(r => `<tr><td><b>${esc(r.name)}</b> <span class="muted">(${r.reusable ? 'Reusable' : 'Consumable'})</span></td>
      <td><input type="number" min="0" id="cl_${r.id}" value="${cur[r.id] ?? ''}" placeholder="None" style="width:110px"></td>
      <td><button class="btn sm" data-save="${r.id}">Save Claim</button></td></tr>`).join('')}</tbody></table></div>
    <div class="toolbar claims-actions" style="margin-top:16px">
      <button class="btn" id="claims_save_all" type="button">
        <svg class="btn-icon save-icon" viewBox="0 0 24 24" aria-hidden="true" focusable="false"><path d="M5 3h12l4 4v14H3V3h2Zm2 2v5h10V5H7Zm0 14h10v-7H7v7Zm2-5h6v3H9v-3Z" fill="currentColor"/></svg>
        <svg class="btn-icon loading-icon" viewBox="0 0 24 24" aria-hidden="true" focusable="false"><path d="M12 3a9 9 0 1 0 9 9" fill="none" stroke="currentColor" stroke-linecap="round" stroke-width="2.5"/></svg>
        <span id="claims_save_label">Save Claims</span>
      </button>
      <button class="btn sec" type="button" onclick="closeModal()">Done</button>
    </div>`);
  const saveAll = $('#claims_save_all');
  saveAll.onclick = async () => {
    if (saveAll.disabled) return;
    const claimInputs = resources.map(r => ({ resourceId: r.id, input: $('#cl_' + r.id) }));
    const invalid = claimInputs.find(({ input }) => input.value !== '' && (!input.checkValidity() || !Number.isInteger(input.valueAsNumber)));
    if (invalid) {
      invalid.input.reportValidity();
      return;
    }
    const entries = claimInputs
      .filter(({ input }) => input.value !== '')
      .map(({ resourceId, input }) => ({ resourceId, maxClaim: Number(input.value) }));
    if (!entries.length) return toast('Please specify at least one claim threshold', 'warn');

    const rowSaves = [...document.querySelectorAll('[data-save]')];
    saveAll.disabled = true;
    saveAll.classList.add('is-loading');
    saveAll.setAttribute('aria-busy', 'true');
    $('#claims_save_label').textContent = 'Saving…';
    rowSaves.forEach(button => { button.disabled = true; });
    claimInputs.forEach(({ input }) => { input.disabled = true; });
    let saved = 0;
    try {
      for (const claim of entries) {
        await PUT(`/api/organizations/${o.id}/claims`, claim);
        saved += 1;
      }
      toast(`Saved ${saved} ${saved === 1 ? 'claim' : 'claims'}`);
    } catch (e) {
      const detail = saved ? `Saved ${saved} of ${entries.length} claims. ` : '';
      toast(detail + e.message, 'err');
    } finally {
      saveAll.disabled = false;
      saveAll.classList.remove('is-loading');
      saveAll.removeAttribute('aria-busy');
      $('#claims_save_label').textContent = 'Save Claims';
      rowSaves.forEach(button => { button.disabled = false; });
      claimInputs.forEach(({ input }) => { input.disabled = false; });
    }
  };
  document.querySelectorAll('[data-save]').forEach(b => b.onclick = async () => {
    const v = $('#cl_' + b.dataset.save).value;
    if (v === '') return toast('Please specify a claim threshold', 'warn');
    try { await PUT(`/api/organizations/${o.id}/claims`, { resourceId: Number(b.dataset.save), maxClaim: Number(v) }); toast('Declared claim saved'); } catch (e) { toast(e.message, 'err'); }
  });
}

// ---------- 4. Requests Queue (Layout A: Wide Photographic Header) ----------
const STATUSES = ['PENDING', 'WAITING', 'PROCESSING', 'ALLOCATED', 'COMPLETED', 'REJECTED'];
async function pgRequests() {
  const canDispatch = ['SYSTEM_ADMIN', 'DISPATCHER'].includes(signedInUser?.role);
  const [list, orgs, resources] = await Promise.all([GET('/api/requests'), GET('/api/organizations'), GET('/api/resources')]);
  view().innerHTML = `
    <div class="section-hero layout-a hero-requests-queue">
      <div class="section-hero-bg"></div>
      <div class="section-hero-content">
        <div class="section-hero-title">Emergency Requests Queue</div>
        <div class="section-hero-desc">Live intake queue for emergency resource allocations. Requests are ordered by strict priority (1–5) and arrival sequence.</div>
        <div class="section-hero-meta">
          <span class="badge"><span class="status-dot info"></span> ${list.length} Total Requests Recorded</span>
          <span class="badge ok">${list.filter(r => r.status === 'ALLOCATED').length} Allocated</span>
        </div>
      </div>
    </div>
    <div class="panel">
      <h2>Submit Resource Request</h2>
      <div class="formgrid">
        <div><label>Requesting Organization</label><select id="rq_org" style="width:100%">${opts(orgs.filter(o => o.active), 'id', 'name')}</select></div>
        <div><label>Requested Resource</label><select id="rq_res" style="width:100%">${opts(resources.filter(r => r.active !== false), 'id', r => `${r.name} (${r.availableQuantity} available)`)}</select></div>
        <div><label>Quantity</label><input id="rq_qty" type="number" min="1" value="1" style="width:100%"></div>
        <div><label>Priority (5 = Life-Threatening)</label><select id="rq_pri" style="width:100%">${[5, 4, 3, 2, 1].map(p => `<option value="${p}" ${p === 3 ? 'selected' : ''}>Priority ${p}</option>`).join('')}</select></div>
        <div><label>Work Units (Burst Duration)</label><input id="rq_work" type="number" min="1" value="1" style="width:100%"></div>
        <div><button class="btn" id="rq_submit" style="width:100%">Submit Request</button></div>
      </div>
    </div>
    <div class="panel" style="margin-top:20px">
      <h2>Active Queue & Intake History</h2>
      ${filterBar('qf', 'Search organization or resource…', [{ key: 'st', label: 'All statuses', options: STATUSES }])}
      <div id="rqTable"></div>
    </div>`;
  $('#rq_submit').onclick = () => act(() => POST('/api/requests', { organizationId: num('rq_org'), resourceId: num('rq_res'), quantity: num('rq_qty'), priority: num('rq_pri'), workUnits: num('rq_work') }),
    r => `Request #${r.id} submitted into queue`);
  const draw = (q = '', f = {}) => {
    const rows = list.filter(r => (!q || (r.organizationName + r.resourceName).toLowerCase().includes(q)) && (!f.st || r.status === f.st));
    $('#rqTable').innerHTML = table([
      { h: 'ID', v: r => '#' + r.id }, { h: 'Organization', v: r => r.organizationName }, { h: 'Resource', v: r => r.resourceName },
      { h: 'Req', num: 1, v: r => r.requestedQuantity }, { h: 'Alloc', num: 1, v: r => r.allocatedQuantity }, { h: 'Prio', num: 1, html: r => `<span class="pill ${r.priority >= 4 ? 'warn' : ''}">${r.priority}</span>` },
      { h: 'Work', num: 1, v: r => r.workUnits }, { h: 'Status', html: r => pill(r.status) }, { h: 'Disposition Reason', v: r => r.statusReason || '—' },
      { h: 'Submitted At', v: r => fmtTime(r.arrivalTime) },
      { h: 'Timeline', html: r => `<button class="btn sm sec" data-timeline="${r.id}" aria-label="Open timeline for request ${r.id}">View</button>` },
      { h: 'Dispatch Actions', html: r => canDispatch && ['PENDING', 'WAITING'].includes(r.status) ? `<button class="btn sm" data-alloc="${r.id}">Allocate</button> <button class="btn sm danger" data-cancel="${r.id}">Reject</button>` : '' }
    ], rows, 'No matching requests in queue');
    const t = $('#rqTable');
    t.querySelectorAll('[data-timeline]').forEach(b => b.onclick = async () => {
      try {
        const events = await GET(`/api/requests/${b.dataset.timeline}/timeline`);
        openModal(`<h2>Request #${esc(b.dataset.timeline)} timeline</h2><ol class="request-timeline">${events.length ? events.map(e =>
          `<li><time>${esc(fmtTime(e.createdAt))}</time><b>${esc(String(e.eventType).replaceAll('_', ' '))}</b><span class="muted">${esc([e.fromStatus, e.toStatus].filter(Boolean).join(' → '))}</span></li>`
        ).join('') : '<li class="muted">No timeline events have been recorded for this request.</li>'}</ol><button class="btn sec" onclick="closeModal()">Close</button>`);
      } catch (e) { toast(e.message, 'err'); }
    });
    t.querySelectorAll('[data-alloc]').forEach(b => b.onclick = () => act(() => POST(`/api/requests/${b.dataset.alloc}/allocate`), r => `#${r.requestId}: ${r.outcome} — ${r.message}${r.bankersNote ? ' | ' + r.bankersNote : ''}`));
    t.querySelectorAll('[data-cancel]').forEach(b => b.onclick = () => { if (confirm('Reject this request?')) act(() => PUT(`/api/requests/${b.dataset.cancel}/status`, { status: 'REJECTED', reason: 'Rejected by operator' }), 'Request rejected'); });
  };
  draw(); wireFilter('qf', [{ key: 'st' }], draw);
}

// ---------- 5. Allocation Center (Layout A: Wide Photographic Header) ----------
function allocationReleaseModal(allocation, refresh) {
  const remaining = Number(allocation.remainingQuantity ?? allocation.quantity);
  openModal(`<form id="releaseAllocationForm" novalidate>
    <h2>Release Resource Units</h2>
    <p class="muted">Return reusable units to available inventory. This action is recorded in the allocation history.</p>
    <div class="panel" style="padding:14px;margin:14px 0">
      <b>${esc(allocation.resourceName)}</b><div class="muted">Allocation #${esc(allocation.id)} · ${esc(allocation.organizationName)}</div>
      <div>Allocated: ${esc(allocation.quantity)} · Already released: ${esc(allocation.releasedQuantity ?? 0)} · Remaining: <b>${remaining}</b></div>
    </div>
    <label for="releaseQuantity">Quantity to release</label>
    <input id="releaseQuantity" type="number" min="1" max="${remaining}" step="1" value="${remaining}" required style="width:100%">
    <p id="releaseValidation" class="form-feedback" role="alert" aria-live="polite"></p>
    <div class="toolbar" style="margin-top:18px">
      <button class="btn" id="confirmRelease" type="submit">Save Release</button>
      <button class="btn sec" id="cancelRelease" type="button">Cancel</button>
    </div>
  </form>`);
  const form = $('#releaseAllocationForm');
  const submit = $('#confirmRelease');
  const quantity = $('#releaseQuantity');
  const feedback = $('#releaseValidation');
  $('#cancelRelease').onclick = () => closeModal();
  quantity.oninput = () => { feedback.textContent = ''; };
  form.onsubmit = async event => {
    event.preventDefault();
    const count = Number(quantity.value);
    if (!Number.isInteger(count) || count < 1 || count > remaining) {
      feedback.textContent = `Enter a whole number from 1 to ${remaining}.`;
      quantity.focus();
      return;
    }
    submit.disabled = true;
    $('#cancelRelease').disabled = true;
    submit.textContent = 'Saving…';
    feedback.textContent = '';
    try {
      await POST(`/api/allocations/${allocation.id}/release`, { quantity: count });
      closeModal();
      toast(`${count} ${allocation.resourceName} unit(s) returned to inventory`, 'ok');
      try {
        await GET('/api/resources');
        await refresh();
      } catch (error) {
        toast(`Release saved, but the displayed data could not refresh: ${error.message}`, 'err');
      }
    } catch (error) {
      feedback.textContent = error.message;
      submit.disabled = false;
      $('#cancelRelease').disabled = false;
      submit.textContent = 'Save Release';
    }
  };
  quantity.focus();
}
async function pgAllocations() {
  const canDispatch = ['SYSTEM_ADMIN', 'DISPATCHER'].includes(signedInUser?.role);
  const [allocs, queue, sys] = await Promise.all([
    GET('/api/allocations'),
    canDispatch ? GET('/api/requests?status=PENDING').then(async p => p.concat(await GET('/api/requests?status=WAITING'))) : Promise.resolve([]),
    canDispatch ? GET('/api/system/status') : Promise.resolve({ failureInjectionEnabled: false })
  ]);
  view().innerHTML = `
    <div class="section-hero layout-a hero-allocation-center">
      <div class="section-hero-bg"></div>
      <div class="section-hero-content">
        <div class="section-hero-title">${canDispatch ? 'Allocation & Dispatch Center' : 'Organisation Allocations'}</div>
        <div class="section-hero-desc">${canDispatch ? 'Batch queue processing powered by operating system schedulers. Every dispatch is guarded by pessimistic row-level locks and Dijkstra\'s Banker\'s Algorithm.' : signedInUser?.role === 'ORGANIZATION_MANAGER' ? 'Resources allocated to your organisation and their related requests.' : 'Resources allocated to your requests.'}</div>
        <div class="section-hero-meta">
          ${canDispatch ? `<span class="badge ${queue.length > 0 ? 'bad' : 'ok'}"><span class="status-dot ${queue.length > 0 ? 'critical' : 'success'}"></span> ${queue.length} Request(s) In Queue</span>` : ''}
          <span class="badge"><span class="status-dot info"></span> ${allocs.filter(a => a.status === 'ACTIVE').length} Active Dispatches</span>
        </div>
      </div>
    </div>
    ${canDispatch ? `<div class="panel">
      <h2>Scheduler Queue Dispatch Controls</h2>
      <p class="muted">Select an algorithm to schedule queued demands. Each allocation executes inside a dedicated PostgreSQL ACID transaction.</p>
      <div class="formgrid">
        <div><label>Scheduling Algorithm</label><select id="al_algo" style="width:100%"><option>FCFS</option><option>PRIORITY</option><option>ROUND_ROBIN</option></select></div>
        <div><label>Time Quantum (Round Robin)</label><input id="al_q" type="number" min="1" value="2" style="width:100%"></div>
        ${sys.failureInjectionEnabled ? '<div><label><input type="checkbox" id="al_fail"> Inject Transaction Failure (Rollback Demo)</label></div>' : ''}
        <div><button class="btn sec" id="al_prev" style="width:100%">Preview Order</button></div>
        <div><button class="btn" id="al_run" style="width:100%">Execute Allocation</button></div>
      </div>
      <div id="al_out"></div>
    </div>` : ''}
    <div class="panel" style="margin-top:20px">
      <h2>Active & Completed Allocations</h2>
      ${filterBar('af', 'Search organization or resource…', [{ key: 'st', label: 'All statuses', options: ['ACTIVE', 'RELEASED'] }])}
      <div id="alTable"></div>
    </div>`;
  if (!canDispatch) {
    const rows = allocs;
    const canRelease = signedInUser?.role === 'ORGANIZATION_MANAGER';
    const drawOrg = (q = '', f = {}) => {
      const filtered = rows.filter(a => (!q || (a.organizationName + a.resourceName).toLowerCase().includes(q)) && (!f.st || a.status === f.st));
      $('#alTable').innerHTML = table([
        { h: 'Allocation ID', v: a => '#' + a.id }, { h: 'Request ID', v: a => '#' + a.requestId },
        { h: 'Organization', v: a => a.organizationName }, { h: 'Resource Dispatched', v: a => a.resourceName },
        { h: 'Allocated', num: 1, v: a => a.quantity },
        { h: 'Released', num: 1, v: a => a.releasedQuantity ?? 0 },
        { h: 'Remaining', num: 1, v: a => a.remainingQuantity ?? a.quantity },
        { h: 'Status', html: a => pill(a.status) },
        { h: 'Dispatched At', v: a => fmtTime(a.allocatedAt) }, { h: 'Released At', v: a => fmtTime(a.releasedAt) || '—' },
        ...(canRelease ? [{ h: 'Action', html: a => a.status === 'ACTIVE' && a.reusable && (a.remainingQuantity ?? a.quantity) > 0
          ? `<button class="btn sm sec" data-tenant-rel="${a.id}" aria-label="Release units from allocation ${a.id}">Release Units</button>` : '' }] : [])
      ], filtered, 'No allocation records are available for your permitted scope.');
      $('#alTable').querySelectorAll('[data-tenant-rel]').forEach(button => {
        button.onclick = () => allocationReleaseModal(rows.find(a => String(a.id) === button.dataset.tenantRel), async () => render());
      });
    };
    drawOrg();
    wireFilter('af', [{ key: 'st' }], drawOrg);
    return;
  }
  const algo = () => ({ algorithm: val('al_algo'), quantum: num('al_q') });
  $('#al_prev').onclick = async () => {
    try {
      const p = await GET(`/api/scheduling/queue-preview?algorithm=${algo().algorithm}&quantum=${algo().quantum ?? ''}`);
      $('#al_out').innerHTML = `<div class="result"><b>${esc(p.algorithm)} Projected Execution Sequence:</b> <span class="mono">${p.executionOrder.length ? p.executionOrder.map(i => '#' + i).join(' → ') : '(Queue is empty)'}</span></div>`;
    } catch (e) { toast(e.message, 'err'); }
  };
  $('#al_run').onclick = async () => {
    try {
      const r = await POST('/api/allocations/process-queue', { ...algo(), injectFailure: !!$('#al_fail')?.checked });
      const n = r.outcomes.filter(o => o.outcome === 'ALLOCATED').length;
      toast(`${r.algorithm}: ${n} of ${r.outcomes.length} request(s) allocated`, n ? 'ok' : 'warn');
      await render();
      $('#al_out').innerHTML = `<div class="result"><b>${esc(r.algorithm)}${r.quantum ? ' (Quantum ' + r.quantum + ')' : ''} — Processing Sequence:</b> <span class="mono">${r.processingOrder.map(i => '#' + i).join(' → ') || '(Queue was empty)'}</span>` +
        table([{ h: 'Request ID', v: o => '#' + o.requestId }, { h: 'Outcome', html: o => pill(o.outcome) }, { h: 'Message', v: o => o.message }, { h: 'Banker\'s Safety Evaluation', v: o => o.bankersNote || '—' }], r.outcomes) + '</div>';
    } catch (e) { toast(e.message, 'err'); }
  };
  const draw = (q = '', f = {}) => {
    const rows = allocs.filter(a => (!q || (a.organizationName + a.resourceName).toLowerCase().includes(q)) && (!f.st || a.status === f.st));
    $('#alTable').innerHTML = table([
      { h: 'Allocation ID', v: a => '#' + a.id }, { h: 'Request ID', v: a => '#' + a.requestId }, { h: 'Organization', v: a => a.organizationName },
      { h: 'Resource Dispatched', v: a => a.resourceName }, { h: 'Allocated', num: 1, v: a => a.quantity },
      { h: 'Released', num: 1, v: a => a.releasedQuantity ?? 0 }, { h: 'Remaining', num: 1, v: a => a.remainingQuantity ?? a.quantity }, { h: 'Status', html: a => pill(a.status) },
      { h: 'Dispatched At', v: a => fmtTime(a.allocatedAt) }, { h: 'Released At', v: a => fmtTime(a.releasedAt) || '—' },
      { h: 'Action', html: a => a.status === 'ACTIVE' && a.reusable && (a.remainingQuantity ?? a.quantity) > 0
        ? `<button class="btn sm sec" data-rel="${a.id}">Release Units</button>` : '' }
    ], rows, 'No allocation records logged');
    $('#alTable').querySelectorAll('[data-rel]').forEach(b => b.onclick = () => {
      allocationReleaseModal(rows.find(a => String(a.id) === b.dataset.rel), async () => render());
    });
  };
  draw(); wireFilter('af', [{ key: 'st' }], draw);
}

// ---------- 6. Scheduling Simulator (Layout B: Split Composition) ----------
const COLORS = ['#87CEEB', '#4AADD6', '#DFF3FC', '#6EBBDE', '#A9DDF1', '#5AA9CF', '#BFE8F7', '#78C4E3'];
let simJobs = [{ id: 1, priority: 3, arrivalTime: 0, workUnits: 24 }, { id: 2, priority: 5, arrivalTime: 0, workUnits: 3 }, { id: 3, priority: 1, arrivalTime: 0, workUnits: 3 }];
async function pgScheduling() {
  view().innerHTML = `
    <div class="section-hero layout-b hero-scheduling-simulator">
      <div class="section-hero-content">
        <div class="section-hero-title">Scheduling Simulator</div>
        <div class="section-hero-desc">Interactive process scheduling engine modeling FCFS, Strict Priority, and Round Robin algorithms with live Gantt timeline rendering.</div>
        <div class="section-hero-meta">
          <span class="badge ok">Deterministic Schedulers Active</span>
        </div>
      </div>
      <div class="section-hero-aside"><div class="section-hero-bg"></div></div>
    </div>
    <div class="grid two">
      <div class="panel">
        <h2>Workload Definition</h2>
        <div class="toolbar"><button class="btn sm sec" id="sj_add">+ Add Job</button><button class="btn sm sec" id="sj_queue">Load Real Queue</button></div>
        <div id="sj_table"></div>
        <div class="formgrid" style="margin-top:14px">
          <div><label>Selected Algorithm</label><select id="sj_algo" style="width:100%"><option>FCFS</option><option>PRIORITY</option><option>ROUND_ROBIN</option></select></div>
          <div><label>Time Quantum</label><input id="sj_q" type="number" min="1" value="4" style="width:100%"></div>
          <div><label><input type="checkbox" id="sj_save"> Record Run in DB</label></div>
          <div><button class="btn" id="sj_run" style="width:100%">Run Simulation</button></div>
        </div>
        <p class="muted" style="margin-top:10px">Times represent simulated CPU execution units. All three algorithms are evaluated simultaneously for comparison.</p>
      </div>
      <div class="panel">
        <h2>Execution Timeline & Gantt Chart</h2>
        <div id="sj_out" class="muted">Execute the simulator to render the interactive execution timeline and comparative metric telemetry.</div>
      </div>
    </div>`;
  const drawJobs = () => {
    $('#sj_table').innerHTML = `<div class="tablewrap"><table class="matrix"><thead><tr><th>Job ID</th><th>Priority</th><th>Arrival Time</th><th>Work Units</th><th></th></tr></thead><tbody>${simJobs.map((j, i) =>
      `<tr><td><input data-k="id" data-i="${i}" type="number" value="${j.id}"></td><td><input data-k="priority" data-i="${i}" type="number" min="1" max="5" value="${j.priority}"></td>
      <td><input data-k="arrivalTime" data-i="${i}" type="number" min="0" value="${j.arrivalTime}"></td><td><input data-k="workUnits" data-i="${i}" type="number" min="1" value="${j.workUnits}"></td>
      <td><button class="btn sm danger" data-rm="${i}">×</button></td></tr>`).join('')}</tbody></table></div>`;
    $('#sj_table').querySelectorAll('input').forEach(inp => inp.oninput = () => { simJobs[+inp.dataset.i][inp.dataset.k] = Number(inp.value); });
    $('#sj_table').querySelectorAll('[data-rm]').forEach(b => b.onclick = () => { simJobs.splice(+b.dataset.rm, 1); drawJobs(); });
  };
  drawJobs();
  $('#sj_add').onclick = () => { simJobs.push({ id: Math.max(0, ...simJobs.map(j => j.id)) + 1, priority: 3, arrivalTime: 0, workUnits: 1 }); drawJobs(); };
  $('#sj_queue').onclick = async () => {
    try {
      const q = (await GET('/api/requests?status=PENDING')).concat(await GET('/api/requests?status=WAITING')).sort((a, b) => a.arrivalSeq - b.arrivalSeq);
      if (!q.length) return toast('The operational queue is currently empty', 'warn');
      simJobs = q.map(r => ({ id: r.id, priority: r.priority, arrivalTime: 0, workUnits: r.workUnits })); drawJobs(); toast(`Loaded ${q.length} queued request(s)`);
    } catch (e) { toast(e.message, 'err'); }
  };
  $('#sj_run').onclick = async () => {
    if (!simJobs.length) return toast('Please specify at least one job workload', 'warn');
    try {
      const sel = val('sj_algo'), q = num('sj_q') || 2, save = $('#sj_save').checked;
      const res = {};
      for (const a of ['FCFS', 'PRIORITY', 'ROUND_ROBIN']) res[a] = await POST('/api/scheduling/simulate', { algorithm: a, quantum: q, jobs: simJobs, save: save && a === sel, name: 'Simulator ' + a });
      showSim(res, sel); if (save) toast('Simulation run recorded to database');
    } catch (e) { toast(e.message, 'err'); }
  };
}
function showSim(res, sel) {
  const r = res[sel], ids = [...new Set(r.timeline.map(s => s.jobId))];
  const total = r.makespan || 1;
  $('#sj_out').className = '';
  $('#sj_out').innerHTML = `<h3>${esc(r.algorithm)} — Execution Order: <span class="mono">${r.executionOrder.map(i => '#' + i).join(' → ')}</span></h3>
    <div class="gantt">${r.timeline.map(s => `<div title="Job #${s.jobId}: ${s.start}–${s.end}" style="flex:${s.end - s.start};background:${COLORS[ids.indexOf(s.jobId) % COLORS.length]}">#${s.jobId}</div>`).join('')}</div>
    <div class="muted mono">Timeline: 0 … ${total} work units</div>
    ${table([{ h: 'Job', v: j => '#' + j.id }, { h: 'Prio', num: 1, v: j => j.priority }, { h: 'Arrival', num: 1, v: j => j.arrivalTime }, { h: 'Burst', num: 1, v: j => j.workUnits },
      { h: 'Response', num: 1, v: j => j.response }, { h: 'Waiting', num: 1, v: j => j.waiting }, { h: 'Turnaround', num: 1, v: j => j.turnaround }, { h: 'Completion', num: 1, v: j => j.completion }], r.jobs)}
    <h3 style="margin-top:18px">Comparative Telemetry — All 3 Algorithms</h3>
    ${table([{ h: 'Algorithm', v: a => a }, { h: 'Avg Waiting Time', num: 1, v: a => res[a].averageWaiting.toFixed(2) }, { h: 'Avg Turnaround', num: 1, v: a => res[a].averageTurnaround.toFixed(2) },
      { h: 'Avg Response', num: 1, v: a => res[a].averageResponse.toFixed(2) }, { h: 'Makespan', num: 1, v: a => res[a].makespan }], Object.keys(res))}`;
}

// ---------- 7. Banker's Algorithm Simulator (Layout C: Abstract Composition) ----------
let bk = { n: 5, m: 3, available: [3, 3, 2], max: [[7, 5, 3], [3, 2, 2], [9, 0, 2], [2, 2, 2], [4, 3, 3]], allocation: [[0, 1, 0], [2, 0, 0], [3, 0, 2], [2, 1, 1], [0, 0, 2]], process: 1, request: [1, 0, 2] };
function matrix(title, rows, cols, data) {
  return `<div><h3>${esc(title)}</h3><div class="tablewrap"><table class="matrix"><thead><tr><th></th>${cols.map(c => `<th>${esc(c)}</th>`).join('')}</tr></thead><tbody>${data.map((r, i) => `<tr><th>${esc(rows[i])}</th>${r.map(v => `<td>${v}</td>`).join('')}</tr>`).join('')}</tbody></table></div></div>`;
}
function bankersResult(r, pNames, rNames) {
  const rows = pNames || r.max.map((_, i) => 'P' + i), cols = rNames || r.available.map((_, j) => 'R' + j);
  const seq = r.safeSequence.map(i => rows[i]).join(' → ');
  let html = `<div class="result ${r.safe ? 'good' : 'badr'}"><b>${r.safe ? 'SAFE STATE' : 'UNSAFE STATE DETECTED'}</b> — ${esc(r.explanation)}${r.safe ? `<br>Discovered Safe Sequence: <span class="mono">${esc(seq)}</span>` : ''}</div>
    <h3>Available Resource Vector</h3><table class="matrix"><thead><tr>${cols.map(c => `<th>${esc(c)}</th>`).join('')}</tr></thead><tbody><tr>${r.available.map(v => `<td>${v}</td>`).join('')}</tr></tbody></table>
    <div class="grid cards" style="margin-top:14px">${matrix('Allocation Matrix', rows, cols, r.allocation)}${matrix('Maximum Matrix (Max)', rows, cols, r.max)}${matrix('Need Matrix (Max − Alloc)', rows, cols, r.need)}</div>`;
  if (r.requestEvaluation) {
    const e = r.requestEvaluation;
    html += `<div class="result ${e.granted ? 'good' : 'badr'}"><b>Evaluation for ${esc(rows[e.process])}: Requesting [${e.request.join(', ')}] → ${esc(e.outcome)}</b><br>${esc(e.explanation)}</div>`;
  }
  return html;
}
async function pgBankers() {
  view().innerHTML = `
    <div class="section-hero layout-c hero-bankers-simulator">
      <div class="section-hero-bg"></div>
      <div class="section-hero-content">
        <div class="section-hero-title">Banker's Algorithm Simulator</div>
        <div class="section-hero-desc">Dijkstra's deadlock avoidance protocol testing live operational database state and custom resource matrices to prevent circular wait conditions.</div>
        <div class="section-hero-meta">
          <span class="badge ok">Pessimistic Safety Guard Active</span>
        </div>
      </div>
    </div>
    <div class="grid two">
      <div class="panel">
        <h2>Live System State (Direct from Database)</h2>
        <p class="muted">Available = free stock, Allocation = ACTIVE holdings, Max = declared claims. Evaluates reusable resources.</p>
        <div class="formgrid">
          <div><label>Organization (What-If Demand)</label><select id="lv_org" style="width:100%"></select></div>
          <div><label>Target Resource</label><select id="lv_res" style="width:100%"></select></div>
          <div><label>Quantity</label><input id="lv_qty" type="number" min="1" value="1" style="width:100%"></div>
          <div><button class="btn" id="lv_run" style="width:100%">Evaluate Safety</button></div>
        </div>
        <div id="lv_out"></div>
      </div>
      <div class="panel">
        <h2>Custom Matrix Solver (Interactive Sandbox)</h2>
        <div class="formgrid">
          <div><label>Number of Processes</label><input id="bk_n" type="number" min="1" max="10" value="${bk.n}" style="width:100%"></div>
          <div><label>Resource Types</label><input id="bk_m" type="number" min="1" max="6" value="${bk.m}" style="width:100%"></div>
          <div><button class="btn sec" id="bk_build" style="width:100%">Rebuild Grid</button></div>
        </div>
        <div id="bk_grid" style="margin-top:14px"></div>
        <div class="toolbar" style="margin-top:14px"><button class="btn" id="bk_run">Evaluate Safety</button></div>
        <div id="bk_out"></div>
      </div>
    </div>`;
  // live part
  const live = await GET('/api/bankers/state');
  $('#lv_org').innerHTML = '<option value="">(None — System Safety Only)</option>' + live.processIds.map((id, i) => `<option value="${id}">${esc(live.processNames[i])}</option>`).join('');
  $('#lv_res').innerHTML = live.resourceIds.map((id, i) => `<option value="${id}">${esc(live.resourceNames[i])}</option>`).join('');
  $('#lv_out').innerHTML = bankersResult(live, live.processNames, live.resourceNames) + (live.warnings.length ? `<p class="muted">${live.warnings.map(esc).join('<br>')}</p>` : '');
  $('#lv_run').onclick = async () => {
    try {
      const q = val('lv_org') ? `?organizationId=${val('lv_org')}&resourceId=${val('lv_res')}&quantity=${val('lv_qty')}` : '';
      const r = await GET('/api/bankers/state' + q); $('#lv_out').innerHTML = bankersResult(r, r.processNames, r.resourceNames);
    } catch (e) { toast(e.message, 'err'); }
  };
  // custom part
  const grid = () => {
    const mat = (name, data) => `<h3>${name}</h3><table class="matrix"><tbody>${data.map((r, i) => `<tr><th>P${i}</th>${r.map((v, j) => `<td><input type="number" min="0" data-m="${name}" data-i="${i}" data-j="${j}" value="${v}"></td>`).join('')}</tr>`).join('')}</tbody></table>`;
    $('#bk_grid').innerHTML = `<h3>Available Resource Vector</h3><table class="matrix"><tbody><tr>${bk.available.map((v, j) => `<td><input type="number" min="0" data-m="available" data-j="${j}" value="${v}"></td>`).join('')}</tr></tbody></table>` +
      mat('max', bk.max).replace('>max<', '>Maximum Matrix (Max)<') + mat('allocation', bk.allocation).replace('>allocation<', '>Allocation Matrix<') +
      `<h3 style="margin-top:14px">Simulated Process Request</h3><div class="toolbar">Process <input id="bk_proc" type="number" min="0" value="${bk.process}" style="width:65px"> requests ${bk.request.map((v, j) => `<input type="number" min="0" data-m="request" data-j="${j}" value="${v}" style="width:65px">`).join('')}
      <label style="display:inline-flex;align-items:center;gap:6px"><input type="checkbox" id="bk_usereq" checked> Include in Evaluation</label></div>`;
    $('#bk_grid').querySelectorAll('input[data-m]').forEach(inp => inp.oninput = () => { const m = inp.dataset.m, v = Number(inp.value); if (m === 'available' || m === 'request') bk[m][+inp.dataset.j] = v; else bk[m][+inp.dataset.i][+inp.dataset.j] = v; });
  };
  grid();
  $('#bk_build').onclick = () => {
    const n = num('bk_n'), m = num('bk_m'); const z = (r, c) => Array.from({ length: r }, () => Array(c).fill(0));
    bk = { n, m, available: Array(m).fill(0), max: z(n, m), allocation: z(n, m), process: 0, request: Array(m).fill(0) }; grid();
  };
  $('#bk_run').onclick = async () => {
    try {
      const body = { available: bk.available, max: bk.max, allocation: bk.allocation };
      if ($('#bk_usereq').checked) { body.process = num('bk_proc'); body.request = bk.request; }
      $('#bk_out').innerHTML = bankersResult(await POST('/api/bankers/evaluate', body));
    } catch (e) { toast(e.message, 'err'); }
  };
}

// ---------- 8. Performance Metrics (Layout C: Abstract Composition) ----------
async function pgMetrics() {
  const m = await GET('/api/metrics');
  const organizationScope = m.scope === 'ORGANIZATION';
  view().innerHTML = `
    <div class="section-hero layout-c hero-performance-metrics">
      <div class="section-hero-bg"></div>
      <div class="section-hero-content">
        <div class="section-hero-title">${organizationScope ? 'Organization Metrics' : 'Performance Metrics & Telemetry'}</div>
        <div class="section-hero-desc">${organizationScope ? 'Request outcomes and processing latency scoped to your organization.' : 'Analytical evaluation of queue latency, scheduling turnaround distributions, and transaction failure recovery audit trails.'}</div>
        <div class="section-hero-meta">
          <span class="badge ok">Historical Telemetry Stream</span>
        </div>
      </div>
    </div>
    <div class="grid two">
      <div class="panel">
        <h2>Request Outcome Distributions</h2>
        ${table([{ h: 'Status', html: r => pill(r.status) }, { h: 'Count', num: 1, v: r => r.count }], m.requestOutcomes, 'No requests recorded yet')}
        <h2 style="margin-top:20px">Queue Intake Latency by Priority</h2>
        ${table([{ h: 'Priority Level', v: r => `Priority ${r.priority}` }, { h: 'Requests', num: 1, v: r => r.requests }, { h: 'Average Latency (s)', num: 1, v: r => r.avg_seconds_to_start.toFixed(2) }], m.startLatencyByPriority, 'No processed requests logged')}
      </div>
        <div class="panel">
        <h2>${organizationScope ? 'Metric Scope' : 'Failure Injection & Rollback Audit Logs'}</h2>
        ${organizationScope ? '<p>Every metric on this page is limited to requests submitted to your organization.</p>' : table([{ h: 'Timestamp', v: r => fmtTime(r.created_at) }, { h: 'Event Type', html: r => pill(r.event_type) }, { h: 'Triggering Request', v: r => r.request_id ? '#' + r.request_id : '—' }, { h: 'Integrity Detail', v: r => r.detail }], m.failureLog, 'No failure events recorded')}
      </div>
    </div>
    ${organizationScope ? '' : `<div class="panel" style="margin-top:20px">
      <h2>Recorded Simulation & Scheduling Runs</h2>
      ${table([
        { h: 'Run ID', v: r => '#' + r.id }, { h: 'Simulation Name', v: r => r.name }, { h: 'Algorithm', v: r => r.algorithm + (r.time_quantum ? ' (q=' + r.time_quantum + ')' : '') },
        { h: 'Workload Size', num: 1, v: r => r.workload_size }, { h: 'Avg Waiting', num: 1, v: r => (r.metrics.avg_waiting_time ?? 0).toFixed(2) },
        { h: 'Avg Turnaround', num: 1, v: r => (r.metrics.avg_turnaround_time ?? 0).toFixed(2) }, { h: 'Avg Response', num: 1, v: r => (r.metrics.avg_response_time ?? 0).toFixed(2) },
        { h: 'Makespan', num: 1, v: r => r.metrics.makespan ?? 0 }, { h: 'Workload Source', html: r => r.is_simulation ? '<span class="pill">Synthetic Workload</span>' : '<span class="pill ok">Live Queue</span>' },
        { h: 'Started At', v: r => fmtTime(r.started_at) }
      ], m.runs, 'No simulation runs recorded yet — run the scheduler on the queue or save a simulation')}
    </div>`}`;
}

// ---------- 9. System Status (Layout B: Split Composition) ----------
async function pgSystem() {
  const s = await GET('/api/system/status');
  const ok = s.inventoryInvariantViolations === 0 && s.allocationLedgerMismatches === 0;
  view().innerHTML = `
    <div class="section-hero layout-b hero-system-status">
      <div class="section-hero-content">
        <div class="section-hero-title">System Status & Database Invariants</div>
        <div class="section-hero-desc">Live PostgreSQL database metrics, HikariCP connection health, ACID transactional invariants, and partial index verification.</div>
        <div class="section-hero-meta">
          <span class="badge ${ok ? 'ok' : 'bad'}"><span class="status-dot ${ok ? 'success' : 'critical'}"></span> ${ok ? 'Invariants Verified' : 'Consistency Anomaly'}</span>
        </div>
      </div>
      <div class="section-hero-aside"><div class="section-hero-bg"></div></div>
    </div>
    <div class="grid cards">
      <div class="card"><div class="n">${esc(s.status)}</div><div class="l">API & Database Health</div></div>
      <div class="card"><div class="n">${s.dbLatencyMs.toFixed(1)} ms</div><div class="l">Query Execution Latency</div></div>
      <div class="card"><div class="n">${s.tables.length}</div><div class="l">Active Relational Tables</div></div>
      <div class="card"><div class="n">${s.indexes.length}</div><div class="l">Custom Performance Indexes</div></div>
    </div>
    <div class="grid two" style="margin-top:20px">
      <div class="panel">
        <h2>Live Consistency & Invariant Verification</h2>
        <p>Database Constraint: <span class="mono">total_quantity = available_quantity + allocated_quantity</span></p>
        <div class="result ${s.inventoryInvariantViolations === 0 ? 'good' : 'badr'}">
          <b>Invariant Violations: ${s.inventoryInvariantViolations}</b><br>
          ${s.inventoryInvariantViolations === 0 ? 'Checked across all resources: zero invariant violations detected.' : 'Warning: Detected records violating the resource conservation law.'}
        </div>
        <p style="margin-top:14px">Cross-Table Check: <span class="mono">allocated_quantity = SUM(active allocations)</span></p>
        <div class="result ${s.allocationLedgerMismatches === 0 ? 'good' : 'badr'}">
          <b>Allocation Ledger Mismatches: ${s.allocationLedgerMismatches}</b><br>
          ${s.allocationLedgerMismatches === 0 ? 'Physical inventory matches active allocation ledger records.' : 'Warning: Mismatch between resource allocated_quantity and active allocations table.'}
        </div>
        <p style="margin-top:16px">Engine Failure Injection Mode: ${s.failureInjectionEnabled ? '<span class="pill warn">ENABLED (Demo Mode)</span>' : '<span class="pill">OFF (Standard ACID)</span>'}</p>
        <p class="muted mono" style="font-size:11px">${esc(s.database)}</p>
      </div>
      <div class="panel">
        <h2>Managed Relational Tables</h2>
        <p class="mono">${s.tables.map(esc).join('<br>')}</p>
        <h2 style="margin-top:18px">Managed Performance Indexes</h2>
        <p class="mono">${s.indexes.map(esc).join('<br>')}</p>
      </div>
    </div>`;
}

async function pgApprovals() {
  const [requests, organizations] = await Promise.all([GET('/api/admin/approvals'), GET('/api/auth/organizations')]);
  view().innerHTML = `<div class="section-hero layout-a hero-account-approvals"><div class="section-hero-bg"></div><div class="section-hero-content"><div class="section-hero-title">Account Approvals</div>
    <div class="section-hero-desc">Review each requested role and organization association before enabling an account.</div></div></div>
    <div class="panel"><h2>Pending registration requests</h2>${table([
      { h: 'Applicant', html: r => `<b>${esc(r.fullName)}</b><br><span class="muted">${esc(r.email)}</span>${r.contactInfo ? `<br><span class="muted">${esc(r.contactInfo)}</span>` : ''}` },
      { h: 'Requested organization', html: r => ['SYSTEM_ADMIN','DISPATCHER'].includes(r.requestedRole)
          ? '<span class="muted">Not applicable — global role</span>'
          : `<b>${esc(r.requestedOrganizationName || '—')}</b>${r.requestedOrganizationId ? `<br><span class="muted">Requested organization #${r.requestedOrganizationId}</span>` : ''}` },
      { h: 'Requested role', v: r => r.requestedRole }, { h: 'Submitted', v: r => fmtTime(r.createdAt) },
      { h: 'Potential matches', html: r => ['SYSTEM_ADMIN','DISPATCHER'].includes(r.requestedRole) ? 'Not applicable'
          : r.possibleMatches?.length ? `<ul class="organization-match-list">${r.possibleMatches.map(o => `<li>${esc(o.name)} — ${esc(o.orgType)}${o.matchKind === 'EXACT' ? ' (exact match)' : ' (possible match)'}</li>`).join('')}</ul>` : 'No similar organization found' },
      { h: 'Decision', html: r => `<button class="btn sm" data-review="${r.id}">Review</button> <button class="btn sm danger" data-reject="${r.id}">Reject</button>` }
    ], requests, 'No account approvals are waiting.')}</div>`;
  view().querySelectorAll('[data-review]').forEach(button => button.onclick = () => {
    const request = requests.find(row => String(row.id) === button.dataset.review);
    if (!request) return;
    if (['SYSTEM_ADMIN','DISPATCHER'].includes(request.requestedRole)) {
      const roleLabel = request.requestedRole === 'SYSTEM_ADMIN' ? 'System Administrator' : 'Dispatcher';
      openModal(`<h2>Review privileged-role request</h2><p><b>${esc(request.fullName)}</b> · ${esc(request.email)}</p>
        <p>Requested role: <b>${roleLabel}</b></p>
        <div class="organization-state error" role="alert">Approving grants global ${roleLabel} access. Review this request carefully. Only an existing System Administrator can approve it.</div>
        <label for="reviewNote">Review note</label><input id="reviewNote" maxlength="300" placeholder="Required decision note">
        <div class="toolbar"><button class="btn" id="approvePrivilegedRole">Approve ${roleLabel} request</button><button class="btn sec" type="button" onclick="closeModal()">Cancel</button></div>`);
      $('#approvePrivilegedRole').onclick = async () => {
        const note = val('reviewNote').trim();
        if (!note) { toast('Add a review note before approving privileged access', 'warn'); return; }
        const save = $('#approvePrivilegedRole'); save.disabled = true;
        await act(() => POST(`/api/admin/approvals/${request.id}/approve`, {
          organizationRole: request.requestedRole, organizationId: null, reviewNote: note
        }), `Approved ${roleLabel} access`);
        closeModal();
      };
      return;
    }
    const existing = organizations.map(o => `<option value="${o.id}" ${String(o.id) === String(request.requestedOrganizationId ?? request.matchedOrganizationId ?? '') ? 'selected' : ''}>${esc(o.name)} — ${esc(o.orgType)}</option>`).join('');
    openModal(`<h2>Review registration</h2><p><b>${esc(request.fullName)}</b> · ${esc(request.email)}</p>
      <p>Requested role: <b>${esc(request.requestedRole.replaceAll('_', ' '))}</b><br>Requested organization: <b>${esc(request.requestedOrganizationName || '—')}</b></p>
      ${request.possibleMatches?.length ? `<div class="organization-state error" role="status">Potential matches: ${request.possibleMatches.map(o => `${esc(o.name)} — ${esc(o.orgType)}`).join('; ')}</div>` : ''}
      <label for="reviewRole">Approved organization role</label><select id="reviewRole"><option value="ORGANIZATION_MEMBER" ${request.requestedRole === 'ORGANIZATION_MEMBER' ? 'selected' : ''}>Organization Member</option><option value="ORGANIZATION_MANAGER" ${request.requestedRole === 'ORGANIZATION_MANAGER' ? 'selected' : ''}>Organization Manager</option></select>
      <label for="reviewOrganization">Approved organization</label><select id="reviewOrganization"><option value="">Choose an active organization</option>${existing}</select>
      <label for="reviewNote">Review note</label><input id="reviewNote" maxlength="300" placeholder="Optional decision note">
      <div class="toolbar"><button class="btn" id="approveAssociation">Approve and associate</button>${request.requestedRole === 'ORGANIZATION_MANAGER' ? '<button class="btn sec" id="createRequestedOrganization">Approve new organization…</button>' : ''}<button class="btn sec" type="button" onclick="closeModal()">Cancel</button></div>`);
    $('#approveAssociation').onclick = async () => {
      const organizationId = Number(val('reviewOrganization'));
      if (!organizationId) { toast('Choose an active organization before approval', 'warn'); return; }
      const save = $('#approveAssociation'); save.disabled = true;
      await act(() => POST(`/api/admin/approvals/${request.id}/approve`, {
        organizationRole: val('reviewRole'), organizationId, reviewNote: val('reviewNote') || null
      }), 'Account approved with organization association');
      closeModal();
    };
    $('#createRequestedOrganization')?.addEventListener('click', () => {
      openModal(`<h2>Approve new organization</h2><p>The organization record and manager membership are created together when you approve.</p>
      <label for="newOrgName">Organization Name</label><input id="newOrgName" value="${esc(request.requestedOrganizationName)}" maxlength="120" required>
      <label for="newOrgType">Organization Type</label><select id="newOrgType">${TYPES.map(type => `<option>${type}</option>`).join('')}</select>
      <label for="newOrgLocation">Location</label><input id="newOrgLocation" maxlength="120" required>
      <label for="newOrgContact">Contact Information</label><input id="newOrgContact" maxlength="200">
      <label for="newOrgNote">Review Note</label><input id="newOrgNote" maxlength="300">
      <div class="toolbar"><button class="btn" id="approveNewOrganization">Create organization and approve manager</button><button class="btn sec" type="button" onclick="closeModal()">Cancel</button></div>`);
      $('#approveNewOrganization')?.addEventListener('click', async () => {
        const button = $('#approveNewOrganization'); button.disabled = true;
        await act(() => POST(`/api/admin/approvals/${request.id}/approve-new-organization`, {
          name: val('newOrgName').trim(), orgType: val('newOrgType'), location: val('newOrgLocation').trim(),
          contactInfo: val('newOrgContact').trim() || null, organizationRole: 'ORGANIZATION_MANAGER', reviewNote: val('newOrgNote') || null
        }), 'Organization created and manager approved');
        closeModal();
      });
    });
  });
  view().querySelectorAll('[data-reject]').forEach(button => button.onclick = async () => {
    if (!confirm('Reject this registration application?')) return;
    button.disabled = true;
    await act(() => POST(`/api/admin/approvals/${button.dataset.reject}/reject`, { reviewNote: 'Rejected by administrator' }), 'Registration rejected');
  });
}

async function pgUsers() {
  const [users, organizations] = await Promise.all([GET('/api/admin/users'), GET('/api/auth/organizations')]);
  view().innerHTML = `<div class="section-hero layout-a hero-user-management"><div class="section-hero-bg"></div><div class="section-hero-content"><div class="section-hero-title">User Management</div>
    <div class="section-hero-desc">Manage approved accounts and their roles.</div></div></div>
    <div class="panel"><h2>Registered accounts</h2>${table([
      { h: 'Name', v: u => u.fullName }, { h: 'Email', v: u => u.email }, { h: 'Status', html: u => pill(u.status) },
      { h: 'Role', v: u => u.globalRole || u.organizationRole || '—' }, { h: 'Organization', v: u => u.organizationId ?? '—' },
      { h: 'Role update', html: u => `<select data-role="${u.id}" aria-label="Role for ${esc(u.email)}"><option value="SYSTEM_ADMIN">System admin</option><option value="DISPATCHER">Dispatcher</option><option value="ORGANIZATION_MANAGER">Organization manager</option><option value="ORGANIZATION_MEMBER">Organization member</option></select> <button class="btn sm sec" data-save-role="${u.id}" data-org="${u.organizationId ?? ''}">Save</button>` }
    ], users, 'No user accounts found.')}</div>`;
  view().querySelectorAll('[data-save-role]').forEach(button => button.onclick = async () => {
    const role = view().querySelector(`[data-role="${button.dataset.saveRole}"]`).value;
    const globalRole = ['SYSTEM_ADMIN', 'DISPATCHER'].includes(role) ? role : null;
    const organizationRole = globalRole ? null : role;
    const organizationId = button.dataset.org ? Number(button.dataset.org) : (organizations[0] ? Number(organizations[0].id) : null);
    if (!globalRole && !organizationId) { toast('Choose or create an organization first', 'err'); return; }
    button.disabled = true;
    await act(() => PUT(`/api/admin/users/${button.dataset.saveRole}/role`, { globalRole, organizationRole, organizationId }), 'User role updated');
  });
}

async function pgMembers() {
  const isAdmin = signedInUser?.role === 'SYSTEM_ADMIN';
  const orgs = isAdmin ? await GET('/api/auth/organizations') : [];
  const initialOrg = signedInUser?.organizationId ?? orgs[0]?.id;
  view().innerHTML = `<div class="section-hero layout-a hero-organization-members"><div class="section-hero-bg"></div><div class="section-hero-content"><div class="section-hero-title">Organization Members</div>
    <div class="section-hero-desc">Review members and manage their membership status within this organization.</div></div></div>
    ${isAdmin ? `<div class="panel"><label for="memberOrganization">Organization</label><select id="memberOrganization">${orgs.map(o => `<option value="${o.id}" ${String(o.id) === String(initialOrg) ? 'selected' : ''}>${esc(o.name)} — ${esc(o.orgType)}</option>`).join('')}</select></div>` : ''}
    <div id="memberList" class="panel" style="margin-top:16px"></div>`;
  const load = async organizationId => {
    const members = await GET(`/api/organizations/${organizationId}/members`);
    $('#memberList').innerHTML = `<h2>Memberships</h2>${table([
      { h: 'Name', v: m => m.fullName }, { h: 'Email', v: m => m.email }, { h: 'Role', v: m => m.role },
      { h: 'Account', html: m => pill(m.accountStatus) }, { h: 'Membership', html: m => pill(m.membershipStatus) },
      { h: 'Joined', v: m => fmtTime(m.createdAt) },
      { h: 'Action', html: m => m.id === signedInUser.id ? 'Current account' : `<button class="btn sm ${m.membershipStatus === 'ACTIVE' ? 'danger' : 'sec'}" data-member="${m.id}" data-status="${m.membershipStatus === 'ACTIVE' ? 'DISABLED' : 'ACTIVE'}">${m.membershipStatus === 'ACTIVE' ? 'Disable' : 'Enable'}</button>` }
    ], members, 'No memberships found.')}`;
    $('#memberList').querySelectorAll('[data-member]').forEach(button => button.onclick = async () => {
      button.disabled = true;
      await act(() => PUT(`/api/organizations/${organizationId}/members/${button.dataset.member}/status`, { status: button.dataset.status }),
        `Membership ${button.dataset.status.toLowerCase()}`);
    });
  };
  const selectedOrg = isAdmin ? val('memberOrganization') : initialOrg;
  if (selectedOrg) await load(selectedOrg);
  $('#memberOrganization')?.addEventListener('change', () => load(val('memberOrganization')).catch(e => toast(e.message, 'err')));
}

async function pgAlerts() {
  const alerts = await GET('/api/alerts');
  view().innerHTML = `<div class="section-hero layout-a hero-alerts"><div class="section-hero-bg"></div><div class="section-hero-content"><div class="section-hero-title">Operational Alerts</div>
    <div class="section-hero-desc">Open inventory alerts generated from live resource conditions.</div></div></div>
    <div class="panel alert-center"><h2>Open Alerts</h2>${alerts.length ? alerts.map(a => `<article class="alert-row ${String(a.level).toLowerCase()}">
      <div><b>${esc(a.title)}</b><p>${esc(a.message)}</p><small>${esc(a.level)} · ${esc(a.source)} · ${fmtTime(a.createdAt)}</small></div>
      ${a.acknowledgedAt ? `<span class="pill info">Acknowledged ${esc(fmtTime(a.acknowledgedAt))}</span>` : `<button class="btn sm sec" data-ack="${a.id}">Acknowledge</button>`}</article>`).join('') : '<p class="muted">No open resource alerts.</p>'}</div>`;
  view().querySelectorAll('[data-ack]').forEach(button => button.onclick = async () => {
    button.disabled = true; await act(() => POST(`/api/alerts/${button.dataset.ack}/acknowledge`), 'Alert acknowledged');
  });
}

async function pgAudit() {
  const rows = await GET('/api/admin/audit-logs?limit=200');
  view().innerHTML = `<div class="section-hero layout-a hero-audit-logs"><div class="section-hero-bg"></div><div class="section-hero-content"><div class="section-hero-title">Audit Logs</div>
    <div class="section-hero-desc">Administrative and operational changes recorded by DRAS.</div></div></div>
    <div class="panel">${table([
      { h: 'Time', v: r => fmtTime(r.createdAt) }, { h: 'Actor', v: r => r.actorName ? `${r.actorName} (${r.actorEmail})` : 'System' },
      { h: 'Action', v: r => r.action }, { h: 'Target', v: r => `${r.targetType || '—'} ${r.targetId || ''}` },
      { h: 'Details', v: r => typeof r.details === 'string' ? r.details : JSON.stringify(r.details ?? {}) }
    ], rows, 'No audit records are available.')}</div>`;
}

render();

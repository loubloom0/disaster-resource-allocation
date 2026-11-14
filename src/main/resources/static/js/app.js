/* DRAS 2.0 EOC Command Center. Plain JavaScript; every number shown comes from the Spring Boot REST API. */
'use strict';

// ---------- helpers ----------
const $ = (s, r = document) => r.querySelector(s);
const esc = v => String(v ?? '').replace(/[&<>"']/g, c => ({'&':'&amp;','<':'&lt;','>':'&gt;','"':'&quot;',"'":'&#39;'}[c]));
const fmtTime = t => t ? new Date(t).toLocaleString() : '';
const view = () => $('#view');

async function api(method, url, body) {
  const opts = { method, headers: {} };
  if (body !== undefined) { opts.headers['Content-Type'] = 'application/json'; opts.body = JSON.stringify(body); }
  let res;
  try { res = await fetch(url, opts); }
  catch (e) { throw new Error('Cannot reach the server: ' + e.message); }
  if (res.status === 204) return null;
  const text = await res.text();
  let data = null;
  try { data = text ? JSON.parse(text) : null; } catch { data = { message: text }; }
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
  system: ['System Status', pgSystem]
};
async function render() {
  const key = (location.hash || '#dashboard').slice(1);
  const [title, fn] = pages[key] || pages.dashboard;
  $('#pageTitle').textContent = title;
  document.querySelectorAll('#nav a').forEach(a => a.classList.toggle('active', a.getAttribute('href') === '#' + key));
  $('#sidebar').classList.remove('open');
  view().className = 'view-transition';
  try { await fn(); } catch (e) { view().innerHTML = `<div class="panel"><h2>Operational Alert</h2><b>Could not load this page.</b><p class="muted">${esc(e.message)}</p></div>`; toast(e.message, 'err'); }
  pingHealth();
}
async function pingHealth() {
  const b = $('#dbBadge');
  try {
    const h = await GET('/api/health');
    b.innerHTML = `<span class="status-dot success"></span> DB Active · ${h.organizations} Orgs · ${h.resources} Resources`;
    b.className = 'badge ok';
  } catch {
    b.innerHTML = `<span class="status-dot critical"></span> Database Unreachable`;
    b.className = 'badge bad';
  }
}
window.addEventListener('hashchange', render);
$('#menuBtn').addEventListener('click', () => $('#sidebar').classList.toggle('open'));

// ---------- 1. Command Center (Layout A: Wide Photographic Header) ----------
async function pgDashboard() {
  const s = await GET('/api/dashboard/stats');
  const card = (n, l) => `<div class="card"><div class="n">${esc(n)}</div><div class="l">${esc(l)}</div></div>`;
  view().innerHTML = `
    <div class="section-hero layout-a hero-command-center">
      <div class="section-hero-bg"></div>
      <div class="section-hero-content">
        <div class="section-hero-title">Emergency Operations Center</div>
        <div class="section-hero-desc">Global command overview of disaster logistics, live resource allocation states, registered relief agencies, and queue metrics.</div>
        <div class="section-hero-meta">
          <span class="badge ok"><span class="status-dot success"></span> Systems Operational</span>
          <span class="badge"><span class="status-dot info"></span> ${s.activeAllocations} Active Deployments</span>
          ${s.pendingRequests > 0 ? `<span class="badge bad"><span class="status-dot critical"></span> ${s.pendingRequests} Pending Demands</span>` : '<span class="badge ok">Queue Clear</span>'}
        </div>
      </div>
    </div>
    <div class="grid cards">
      ${card(s.totalUnits, 'Total Inventory Units')}${card(s.availableUnits, 'Available Reserves')}${card(s.allocatedUnits, 'Active Dispatched')}
      ${card(s.organizations, 'Registered Organizations')}${card(s.pendingRequests, 'Pending In Queue')}${card(s.completedAllocations, 'Allocations Logged')}
      ${card(s.activeAllocations, 'Active Deployments')}
    </div>
    <div class="grid two" style="margin-top:20px">
      <div class="panel"><h2>Resource Utilisation</h2>${table([
        { h: 'Resource', v: r => r.name }, { h: 'Type', html: r => `<span class="pill ${r.reusable ? 'info' : ''}">${r.reusable ? 'Reusable' : 'Consumable'}</span>` },
        { h: 'Total', num: 1, v: r => r.totalQuantity }, { h: 'Available', num: 1, v: r => r.availableQuantity }, { h: 'Allocated', num: 1, v: r => r.allocatedQuantity },
        { h: 'Capacity', html: r => `<div class="bar"><i style="width:${r.totalQuantity ? Math.min(100, Math.round(100 * r.allocatedQuantity / r.totalQuantity)) : 0}%"></i></div>` }
      ], s.resources)}</div>
      <div class="panel"><h2>Requests by Status</h2>${table([{ h: 'Status', html: r => pill(r.status) }, { h: 'Count', num: 1, v: r => r.count }], s.requestsByStatus, 'No requests logged')}
        <h2 style="margin-top:20px">Quick Operational Actions</h2>
        <div class="toolbar">
          <a class="btn" href="#requests">Submit Request</a>
          <a class="btn sec" href="#allocations">Process Queue</a>
          <a class="btn sec" href="#bankers">Banker's Check</a>
        </div>
      </div>
    </div>
    <div class="panel" style="margin-top:20px"><h2>Recent Operational Activity (Resource Audit History)</h2>${table([
      { h: 'Timestamp', v: r => fmtTime(r.createdAt) }, { h: 'Resource', v: r => r.resourceName }, { h: 'Action', v: r => r.action },
      { h: 'Delta', num: 1, v: r => r.quantityChange }, { h: 'Inventory Transition', v: r => `${r.availableBefore} → ${r.availableAfter}` }, { h: 'Audit Note', v: r => r.note }
    ], s.recentActivity, 'No audit records logged yet')}</div>`;
}

// ---------- 2. Resource Inventory (Layout B: Split Composition) ----------
const CATS = ['AMBULANCE', 'MEDICINE', 'FOOD', 'WATER', 'HOSPITAL_BED', 'MEDICAL_STAFF'];
async function pgInventory() {
  const list = await GET('/api/resources');
  view().innerHTML = `
    <div class="section-hero layout-b hero-resource-inventory">
      <div class="section-hero-content">
        <div class="section-hero-title">Resource Inventory</div>
        <div class="section-hero-desc">Logistics monitoring of physical disaster relief reserves, warehouses, emergency vehicles, and clinical personnel.</div>
        <div class="section-hero-meta">
          <button class="btn" id="addRes">+ Add Resource</button>
        </div>
      </div>
      <div class="section-hero-aside"><div class="section-hero-bg"></div></div>
    </div>
    <div class="panel">
      <div class="toolbar"><h2 style="margin:0;flex:1">Inventory Records</h2></div>
      ${filterBar('rf', 'Search resource name or location…', [{ key: 'cat', label: 'All categories', options: CATS }])}
      <div id="resTable"></div>
    </div>
    <div class="panel" style="margin-top:20px">
      <h2>Resource History Audit Trail</h2>
      <div class="toolbar">
        <select id="histRes"><option value="">All resources</option>${opts(list, 'id', 'name')}</select>
      </div>
      <div id="histTable"></div>
    </div>`;
  const draw = (q = '', f = {}) => {
    const rows = list.filter(r => (!q || (r.name + r.location).toLowerCase().includes(q)) && (!f.cat || r.category === f.cat));
    $('#resTable').innerHTML = table([
      { h: 'ID', v: r => r.id }, { h: 'Resource Name', v: r => r.name }, { h: 'Category', v: r => r.category }, { h: 'Location', v: r => r.location },
      { h: 'Total', num: 1, v: r => r.totalQuantity }, { h: 'Available', num: 1, v: r => r.availableQuantity }, { h: 'Allocated', num: 1, v: r => r.allocatedQuantity },
      { h: 'Kind', html: r => `<span class="pill ${r.reusable ? 'info' : ''}">${r.reusable ? 'Reusable' : 'Consumable'}</span>` },
      { h: 'Status', html: r => r.active ? '<span class="pill ok">Active</span>' : '<span class="pill">Inactive</span>' },
      { h: 'Actions', html: r => `<button class="btn sm sec" data-edit="${r.id}">Edit</button> <button class="btn sm danger" data-del="${r.id}">Delete</button>` }
    ], rows, 'No matching resource found');
    $('#resTable').querySelectorAll('[data-edit]').forEach(b => b.onclick = () => resourceForm(list.find(r => r.id == b.dataset.edit)));
    $('#resTable').querySelectorAll('[data-del]').forEach(b => b.onclick = () => { if (confirm('Delete this resource?')) act(() => DEL('/api/resources/' + b.dataset.del), 'Resource deleted'); });
  };
  draw(); wireFilter('rf', [{ key: 'cat' }], draw);
  $('#addRes').onclick = () => resourceForm(null);
  const hist = async () => {
    const id = val('histRes');
    const h = await GET('/api/resources/history?limit=30' + (id ? '&resourceId=' + id : ''));
    $('#histTable').innerHTML = table([
      { h: 'Timestamp', v: r => fmtTime(r.createdAt) }, { h: 'Resource', v: r => r.resourceName }, { h: 'Action', v: r => r.action },
      { h: 'Delta', num: 1, v: r => r.quantityChange }, { h: 'Balance', v: r => `${r.availableBefore} → ${r.availableAfter}` },
      { h: 'Request ID', v: r => r.requestId ? '#' + r.requestId : '—' }, { h: 'Audit Note', v: r => r.note }
    ], h, 'No historical events logged');
  };
  $('#histRes').onchange = hist; hist();
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
  const list = await GET('/api/organizations');
  view().innerHTML = `
    <div class="section-hero layout-b hero-organization-directory">
      <div class="section-hero-content">
        <div class="section-hero-title">Organization Directory</div>
        <div class="section-hero-desc">Directory of participating hospitals, community shelters, and emergency rescue units with declared maximum resource claims.</div>
        <div class="section-hero-meta">
          <button class="btn" id="addOrg">+ Register Organization</button>
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
    const rows = list.filter(o => (!q || (o.name + o.location).toLowerCase().includes(q)) && (!f.type || o.orgType === f.type));
    $('#orgTable').innerHTML = table([
      { h: 'ID', v: o => o.id }, { h: 'Organization Name', v: o => o.name }, { h: 'Type', html: o => `<span class="pill info">${esc(o.orgType)}</span>` },
      { h: 'Location', v: o => o.location }, { h: 'Contact Info', v: o => o.contactInfo || '—' },
      { h: 'Status', html: o => o.active ? '<span class="pill ok">Active</span>' : '<span class="pill">Inactive</span>' },
      { h: 'Actions', html: o => `<button class="btn sm" data-claims="${o.id}">Max Claims</button> <button class="btn sm sec" data-edit="${o.id}">Edit</button> <button class="btn sm danger" data-del="${o.id}">Delete</button>` }
    ], rows, 'No matching organization found');
    const t = $('#orgTable');
    t.querySelectorAll('[data-edit]').forEach(b => b.onclick = () => orgForm(list.find(o => o.id == b.dataset.edit)));
    t.querySelectorAll('[data-claims]').forEach(b => b.onclick = () => claimsForm(list.find(o => o.id == b.dataset.claims)));
    t.querySelectorAll('[data-del]').forEach(b => b.onclick = () => { if (confirm('Delete this organization?')) act(() => DEL('/api/organizations/' + b.dataset.del), 'Organization deleted'); });
  };
  draw(); wireFilter('of', [{ key: 'type' }], draw); $('#addOrg').onclick = () => orgForm(null);
}
function orgForm(o) {
  openModal(`<h2>${o ? 'Edit' : 'Register'} Organization</h2>
    <label>Organization Name</label><input id="f_name" value="${esc(o?.name)}" style="width:100%">
    <label>Organization Type</label><select id="f_type" style="width:100%">${TYPES.map(t => `<option ${o?.orgType === t ? 'selected' : ''}>${t}</option>`).join('')}</select>
    <label>Location / Jurisdiction</label><input id="f_loc" value="${esc(o?.location)}" style="width:100%">
    <label>Contact Info</label><input id="f_contact" value="${esc(o?.contactInfo)}" style="width:100%">
    <label style="margin-top:14px"><input type="checkbox" id="f_active" ${(o?.active ?? true) ? 'checked' : ''}> Active Organization</label>
    <div class="toolbar" style="margin-top:20px"><button class="btn" id="f_save">Save Changes</button><button class="btn sec" onclick="closeModal()">Cancel</button></div>`);
  $('#f_save').onclick = () => {
    const body = { name: val('f_name').trim(), orgType: val('f_type'), location: val('f_loc').trim(), contactInfo: val('f_contact').trim() || null, active: $('#f_active').checked };
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
        <div><label>Requested Resource</label><select id="rq_res" style="width:100%">${opts(resources.filter(r => r.active), 'id', r => `${r.name} (${r.availableQuantity} available)`)}</select></div>
        <div><label>Quantity</label><input id="rq_qty" type="number" min="1" value="1" style="width:100%"></div>
        <div><label>Priority (5 = Life-Threatening)</label><select id="rq_pri" style="width:100%">${[5, 4, 3, 2, 1].map(p => `<option ${p === 3 ? 'selected' : ''}>Priority ${p}</option>`).join('')}</select></div>
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
      { h: 'Dispatch Actions', html: r => ['PENDING', 'WAITING'].includes(r.status) ? `<button class="btn sm" data-alloc="${r.id}">Allocate</button> <button class="btn sm danger" data-cancel="${r.id}">Reject</button>` : '' }
    ], rows, 'No matching requests in queue');
    const t = $('#rqTable');
    t.querySelectorAll('[data-alloc]').forEach(b => b.onclick = () => act(() => POST(`/api/requests/${b.dataset.alloc}/allocate`), r => `#${r.requestId}: ${r.outcome} — ${r.message}${r.bankersNote ? ' | ' + r.bankersNote : ''}`));
    t.querySelectorAll('[data-cancel]').forEach(b => b.onclick = () => { if (confirm('Reject this request?')) act(() => PUT(`/api/requests/${b.dataset.cancel}/status`, { status: 'REJECTED', reason: 'Rejected by operator' }), 'Request rejected'); });
  };
  draw(); wireFilter('qf', [{ key: 'st' }], draw);
}

// ---------- 5. Allocation Center (Layout A: Wide Photographic Header) ----------
async function pgAllocations() {
  const [allocs, queue, sys] = await Promise.all([GET('/api/allocations'), GET('/api/requests?status=PENDING').then(async p => p.concat(await GET('/api/requests?status=WAITING'))), GET('/api/system/status')]);
  view().innerHTML = `
    <div class="section-hero layout-a hero-allocation-center">
      <div class="section-hero-bg"></div>
      <div class="section-hero-content">
        <div class="section-hero-title">Allocation & Dispatch Center</div>
        <div class="section-hero-desc">Batch queue processing powered by operating system schedulers. Every dispatch is guarded by pessimistic row-level locks and Dijkstra's Banker's Algorithm.</div>
        <div class="section-hero-meta">
          <span class="badge ${queue.length > 0 ? 'bad' : 'ok'}"><span class="status-dot ${queue.length > 0 ? 'critical' : 'success'}"></span> ${queue.length} Request(s) In Queue</span>
          <span class="badge"><span class="status-dot info"></span> ${allocs.filter(a => a.status === 'ACTIVE').length} Active Dispatches</span>
        </div>
      </div>
    </div>
    <div class="panel">
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
    </div>
    <div class="panel" style="margin-top:20px">
      <h2>Active & Completed Allocations</h2>
      ${filterBar('af', 'Search organization or resource…', [{ key: 'st', label: 'All statuses', options: ['ACTIVE', 'RELEASED'] }])}
      <div id="alTable"></div>
    </div>`;
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
      { h: 'Resource Dispatched', v: a => a.resourceName }, { h: 'Units', num: 1, v: a => a.quantity }, { h: 'Status', html: a => pill(a.status) },
      { h: 'Dispatched At', v: a => fmtTime(a.allocatedAt) }, { h: 'Released At', v: a => fmtTime(a.releasedAt) || '—' },
      { h: 'Action', html: a => a.status === 'ACTIVE' ? `<button class="btn sm sec" data-rel="${a.id}">Release Units</button>` : '' }
    ], rows, 'No allocation records logged');
    $('#alTable').querySelectorAll('[data-rel]').forEach(b => b.onclick = () => act(() => POST(`/api/allocations/${b.dataset.rel}/release`), 'Allocation released — units returned to inventory'));
  };
  draw(); wireFilter('af', [{ key: 'st' }], draw);
}

// ---------- 6. Scheduling Simulator (Layout B: Split Composition) ----------
const COLORS = ['#e67e22', '#3498db', '#2ecc71', '#9b59b6', '#e74c3c', '#1abc9c', '#f1c40f', '#34495e'];
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
  view().innerHTML = `
    <div class="section-hero layout-c hero-performance-metrics">
      <div class="section-hero-bg"></div>
      <div class="section-hero-content">
        <div class="section-hero-title">Performance Metrics & Telemetry</div>
        <div class="section-hero-desc">Analytical evaluation of queue latency, scheduling turnaround distributions, and transaction failure recovery audit trails.</div>
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
        <h2>Failure Injection & Rollback Audit Logs</h2>
        ${table([{ h: 'Timestamp', v: r => fmtTime(r.created_at) }, { h: 'Event Type', html: r => pill(r.event_type) }, { h: 'Triggering Request', v: r => r.request_id ? '#' + r.request_id : '—' }, { h: 'Integrity Detail', v: r => r.detail }], m.failureLog, 'No failure events recorded')}
      </div>
    </div>
    <div class="panel" style="margin-top:20px">
      <h2>Recorded Simulation & Scheduling Runs</h2>
      ${table([
        { h: 'Run ID', v: r => '#' + r.id }, { h: 'Simulation Name', v: r => r.name }, { h: 'Algorithm', v: r => r.algorithm + (r.time_quantum ? ' (q=' + r.time_quantum + ')' : '') },
        { h: 'Workload Size', num: 1, v: r => r.workload_size }, { h: 'Avg Waiting', num: 1, v: r => (r.metrics.avg_waiting_time ?? 0).toFixed(2) },
        { h: 'Avg Turnaround', num: 1, v: r => (r.metrics.avg_turnaround_time ?? 0).toFixed(2) }, { h: 'Avg Response', num: 1, v: r => (r.metrics.avg_response_time ?? 0).toFixed(2) },
        { h: 'Makespan', num: 1, v: r => r.metrics.makespan ?? 0 }, { h: 'Workload Source', html: r => r.is_simulation ? '<span class="pill">Synthetic Workload</span>' : '<span class="pill ok">Live Queue</span>' },
        { h: 'Started At', v: r => fmtTime(r.started_at) }
      ], m.runs, 'No simulation runs recorded yet — run the scheduler on the queue or save a simulation')}
    </div>`;
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

render();

'use strict';
/* CampusMatch frontend: plain JavaScript, no build step.
   Security notes: all dynamic content is inserted with textContent / createElement (never innerHTML),
   so resume- or job-derived text cannot inject markup. The CSP (set by the server) also blocks inline scripts. */

const app = document.getElementById('app');
const store = {
  get token() { return sessionStorage.getItem('cm_token'); },
  set token(v) { v ? sessionStorage.setItem('cm_token', v) : sessionStorage.removeItem('cm_token'); },
  get name() { return sessionStorage.getItem('cm_name') || ''; },
  set name(v) { v ? sessionStorage.setItem('cm_name', v) : sessionStorage.removeItem('cm_name'); },
};

// ---------- tiny DOM helper ----------
function h(tag, attrs, ...kids) {
  const el = document.createElement(tag);
  for (const [k, v] of Object.entries(attrs || {})) {
    if (v === null || v === undefined || v === false) continue;
    if (k.startsWith('on')) el.addEventListener(k.slice(2).toLowerCase(), v);
    else if (k === 'class') el.className = v;
    else if (['value', 'checked', 'disabled', 'selected'].includes(k)) el[k] = v;
    else el.setAttribute(k, v === true ? '' : v);
  }
  const add = (k) => {
    if (Array.isArray(k)) return k.forEach(add);
    if (k === null || k === undefined || k === false) return;
    el.append(k instanceof Node ? k : document.createTextNode(String(k)));
  };
  kids.forEach(add);
  return el;
}

// ---------- API ----------
async function api(path, { method = 'GET', body, form, keepalive } = {}) {
  const headers = {};
  if (store.token) headers.Authorization = 'Bearer ' + store.token;
  let payload;
  if (form) payload = form;
  else if (body !== undefined) { headers['Content-Type'] = 'application/json'; payload = JSON.stringify(body); }
  const res = await fetch('/api/v1' + path, { method, headers, body: payload, keepalive });
  if (res.status === 401 && store.token) { logout(); throw new Error('Your session expired. Please sign in again.'); }
  if (res.status === 204) return null;
  const data = await res.json().catch(() => null);
  if (!res.ok) throw new Error((data && (data.detail || data.title)) || 'Request failed (' + res.status + ')');
  return data;
}

function logout() { store.token = null; store.name = ''; location.hash = '#/login'; render(); }
const debounce = (fn, ms) => { let t; return (...a) => { clearTimeout(t); t = setTimeout(() => fn(...a), ms); }; };
const notice = (msg, kind) => h('div', { class: 'notice ' + (kind || 'err'), role: kind === 'ok' ? 'status' : 'alert' }, msg);
const chips = (list, cls) => h('div', { class: 'chips' }, (list || []).map(s => h('span', { class: 'chip ' + (cls || '') }, s)));
const fmtDate = (iso) => iso ? new Date(iso).toLocaleDateString() : '';

// ---------- layout / routing ----------
const PROTECTED = ['/profile', '/resume', '/recommended', '/tracker'];

function shell(content, active) {
  const link = (href, label) => h('a', { href: '#' + href, class: active === href ? 'on' : '' }, label);
  return h('div', null,
    h('header', { class: 'top' }, h('div', { class: 'bar' },
      h('a', { class: 'brand', href: '#/jobs' }, 'CampusMatch'),
      h('nav', { 'aria-label': 'Main' },
        link('/jobs', 'Browse jobs'),
        store.token ? [link('/recommended', 'My matches'), link('/resume', 'Resume & ATS'), link('/profile', 'Profile'), link('/tracker', 'Tracker')] : null),
      store.token
        ? h('span', { class: 'row' }, h('span', { class: 'muted' }, store.name), h('button', { class: 'link', onclick: logout }, 'Sign out'))
        : link('/login', 'Sign in'))),
    h('main', null, content));
}

async function render() {
  const path = (location.hash.slice(1) || '/jobs');
  if (PROTECTED.includes(path) && !store.token) { location.hash = '#/login'; return; }
  const views = { '/login': viewAuth, '/register': viewAuth, '/verify': viewVerify, '/jobs': viewJobs, '/profile': viewProfile,
                  '/resume': viewResume, '/recommended': viewRecommended, '/tracker': viewTracker };
  const view = views[path] || viewJobs;
  const holder = h('div', null, h('p', { class: 'loading' }, 'Loading...'));
  app.replaceChildren(shell(holder, path));
  try { holder.replaceChildren(await view(path)); }
  catch (e) { holder.replaceChildren(notice(e.message)); }
}
window.addEventListener('hashchange', render);

// ---------- auth ----------
function viewAuth(path) {
  const isReg = path === '/register';
  const msg = h('div');
  const f = {
    name: h('input', { type:'text', autocomplete:'name', maxlength:100, required:true }),
    email: h('input', { type:'email', autocomplete:'email', required:true, maxlength:254 }),
    phone: h('input', { type:'tel', autocomplete:'tel', required:true, maxlength:20, placeholder:'+91 9876543210' }),
    pw: h('input', { type:'password', autocomplete:isReg?'new-password':'current-password', required:true, maxlength:100 }),
    consent: h('input', { type:'checkbox' })
  };
  const btn=h('button',{type:'submit'},isReg?'Create account':'Sign in');
  const form=h('form',{class:'card auth',novalidate:true,onsubmit:async(e)=>{e.preventDefault();msg.replaceChildren();btn.disabled=true;try{
    const body=isReg?{email:f.email.value,phone:f.phone.value,password:f.pw.value,fullName:f.name.value,consent:f.consent.checked}:{email:f.email.value,password:f.pw.value};
    const r=await api(isReg?'/auth/register':'/auth/login',{method:'POST',body});
    if(isReg){sessionStorage.setItem('cm_verify_email',r.email); if(r.devEmailCode) sessionStorage.setItem('cm_dev_email_code',r.devEmailCode); if(r.devPhoneCode) sessionStorage.setItem('cm_dev_phone_code',r.devPhoneCode); location.hash='#/verify';}
    else {store.token=r.accessToken;store.name=r.fullName;location.hash='#/recommended';}
  }catch(err){msg.replaceChildren(notice(err.message));}finally{btn.disabled=false;}}},
    h('h1',null,isReg?'Create your account':'Welcome back'),
    isReg?h('label',null,'Full name',f.name):null,
    h('label',null,'Email',f.email),
    isReg?h('label',null,'Phone number',f.phone):null,
    h('label',null,'Password',f.pw),
    isReg?h('p',{class:'muted'},'At least 10 characters, with a letter and a number.'):null,
    isReg?h('label',null,f.consent,' I accept the terms and privacy policy.'):null,
    msg,btn,
    h('p',{class:'muted'},isReg?'Already registered? ':'New here? ',h('a',{href:isReg?'#/login':'#/register'},isReg?'Sign in':'Create an account')));
  return form;
}

async function viewVerify(){
  const email=sessionStorage.getItem('cm_verify_email')||''; const msg=h('div');
  const emailCode=h('input',{inputmode:'numeric',maxlength:6,placeholder:'6-digit email code'});
  const phoneCode=h('input',{inputmode:'numeric',maxlength:6,placeholder:'6-digit phone OTP'});
  const devEmail=sessionStorage.getItem('cm_dev_email_code'); const devPhone=sessionStorage.getItem('cm_dev_phone_code');
  const status=h('div');
  if(devEmail||devPhone) status.append(notice('Development mode: Email code '+(devEmail||'not available')+' · Phone OTP '+(devPhone||'not available'),'warn'));
  const verify=async()=>{msg.replaceChildren();try{
    let r=await api('/auth/verify-email',{method:'POST',body:{email,code:emailCode.value}}); if(!r.emailVerified) throw new Error('Email verification failed.');
    r=await api('/auth/verify-phone',{method:'POST',body:{email,code:phoneCode.value}}); if(!r.phoneVerified) throw new Error('Phone verification failed.');
    sessionStorage.removeItem('cm_verify_email');sessionStorage.removeItem('cm_dev_email_code');sessionStorage.removeItem('cm_dev_phone_code');
    store.token=r.accessToken;store.name=r.fullName;msg.replaceChildren(notice('Both email and phone are verified. Complete your profile and upload your resume.','ok'));setTimeout(()=>location.hash='#/profile',400);
  }catch(e){msg.replaceChildren(notice(e.message));}};
  return h('div',{class:'card auth'},h('h1',null,'Verify your account'),h('p',{class:'muted'},'We sent one code to '+email+' and one OTP to your phone. Enter both to activate your account.'),
    h('label',null,'Email verification code',emailCode),h('label',null,'Phone OTP',phoneCode),status,msg,h('button',{type:'button',onclick:verify},'Verify email & phone'),
    h('p',{class:'muted'},'Need new codes? ',h('button',{class:'link',type:'button',onclick:async()=>{try{const r=await api('/auth/resend',{method:'POST',body:{email}});if(r.devEmailCode)sessionStorage.setItem('cm_dev_email_code',r.devEmailCode);if(r.devPhoneCode)sessionStorage.setItem('cm_dev_phone_code',r.devPhoneCode);location.hash='#/verify';}catch(e){msg.replaceChildren(notice(e.message));}}},'Resend')));
}

// ---------- jobs ----------
function applyLink(job) {
  const a = h('a', { class: 'btn', href: job.applyUrl, target: '_blank', rel: 'noopener noreferrer' },
    job.live ? 'View & apply' : 'Apply on ' + (job.applyHost || 'company site'));
  if (store.token) a.addEventListener('click', () => { api('/jobs/' + job.id + '/apply-intent', { method: 'POST', keepalive: true }).catch(() => {}); });
  return a;
}

function jobCard(job, match) {
  const meta = [job.company, job.location, job.workMode.toLowerCase(), job.jobType.toLowerCase()].join(' · ');
  const details = match ? h('details', null, h('summary', null, 'Why this score?'),
    h('table', null, h('tbody', null, match.breakdown.map(c =>
      h('tr', null, h('td', null, c.component), h('td', null, c.points + ' / ' + c.max), h('td', { class: 'muted' }, c.detail))))),
    match.missingRequired.length ? h('p', { class: 'muted' }, 'Required skills you have not listed:') : null,
    match.missingRequired.length ? chips(match.missingRequired, 'miss') : null) : null;
  return h('article', { class: 'card job' },
    h('div', { class: 'row spread' },
      h('div', null, h('h3', null, job.title, ' ', job.live ? h('span', { class: 'badge', title: 'Live listing sourced from employer career data' }, 'live') : (job.source === 'SAMPLE' ? h('span', { class: 'badge', title: 'Demo posting for testing' }, 'sample listing') : null)),
        h('div', { class: 'muted' }, meta)),
      match ? h('div', { 'aria-label': 'Match score' }, h('span', { class: 'score' }, match.matchScore, h('small', null, '%')),
        h('meter', { min: 0, max: 100, low: 40, high: 70, optimum: 100, value: match.matchScore })) : null),
    h('p', null, job.description),
    match ? [chips(match.matchedRequired, 'have'), chips(match.missingRequired, 'miss')] : chips([...job.requiredSkills]),
    details,
    h('div', { class: 'row' }, applyLink(job)));
}

async function viewJobs() {
  const state = { q: '', location: '', workMode: '', type: '', page: 0 };
  const list = h('div'); const pager = h('div', { class: 'pager' });
  const load = async () => {
    list.replaceChildren(h('p', { class: 'loading' }, 'Searching...'));
    try {
      const qs = new URLSearchParams({ q: state.q, location: state.location, workMode: state.workMode, type: state.type, page: state.page, size: 8 });
      const r = await api('/jobs?' + qs);
      list.replaceChildren(...(r.items.length ? r.items.map(j => jobCard(j)) : [h('p', { class: 'muted' }, 'No jobs match these filters.')]));
      pager.replaceChildren(
        h('button', { class: 'ghost', disabled: r.page <= 0, onclick: () => { state.page--; load(); } }, 'Previous'),
        h('span', { class: 'muted' }, 'Page ' + (r.page + 1) + ' of ' + Math.max(1, r.totalPages) + ' · ' + r.total + ' jobs'),
        h('button', { class: 'ghost', disabled: r.page + 1 >= r.totalPages, onclick: () => { state.page++; load(); } }, 'Next'));
    } catch (e) { list.replaceChildren(notice(e.message)); }
  };
  const run = debounce(() => { state.page = 0; load(); }, 300);
  const bind = (key) => (e) => { state[key] = e.target.value; run(); };
  const filters = h('div', { class: 'card grid' },
    h('label', null, 'Search', h('input', { type: 'search', placeholder: 'Title, company or keyword', oninput: bind('q') })),
    h('label', null, 'Location', h('input', { type: 'search', placeholder: 'e.g. Bengaluru', oninput: bind('location') })),
    h('label', null, 'Work mode', h('select', { onchange: bind('workMode') }, ['', 'REMOTE', 'HYBRID', 'ONSITE'].map(v => h('option', { value: v }, v || 'Any')))),
    h('label', null, 'Type', h('select', { onchange: bind('type') }, ['', 'INTERNSHIP', 'FULLTIME', 'PARTTIME', 'CONTRACT', 'TEMPORARY'].map(v => h('option', { value: v }, v || 'Any')))));
  load();
  return h('div', null, h('h1', null, 'Browse jobs'),
    h('p', { class: 'muted' }, 'Each Apply button opens the company\'s own application page. This site never submits an application for you.'),
    filters, list, pager);
}

async function viewRecommended() {
  const r = await api('/me/recommendations?size=20');
  const notes = [];
  if (r.lowConfidence) notes.push(notice('Low confidence: ' + (r.hasResume ? '' : 'upload a resume and ') + 'list your skills in your profile for more accurate matches.', 'warn'));
  if (r.profileCompleteness.percent < 100) notes.push(h('p', { class: 'muted' }, 'Profile ' + r.profileCompleteness.percent + '% complete. Missing: ' + r.profileCompleteness.missing.join(', ') + '.'));
  return h('div', null, h('h1', null, 'Jobs matched to you'),
    h('p', { class: 'muted' }, 'Match score = how well your skills and preferences cover the job\'s stated requirements (rubric v' + r.matcherVersion + '). Open "Why this score?" on any card to see every component.'),
    notes, r.items.map(x => jobCard(x.job, x)));
}

// ---------- profile ----------
async function viewProfile() {
  const p = await api('/me/profile');
  const msg = h('div');
  const inp = (k, attrs) => h('input', { value: p[k] ?? '', ...attrs });
  const f = {
    fullName: inp('fullName', { maxlength: 100 }), phone: inp('phone', { type: 'tel', maxlength: 20 }),
    location: inp('location', { maxlength: 100 }), institution: inp('institution', { maxlength: 150 }),
    degree: inp('degree', { maxlength: 100 }), fieldOfStudy: inp('fieldOfStudy', { maxlength: 100 }),
    gradYear: inp('gradYear', { type: 'number', min: 1990, max: 2100 }),
    experienceYears: inp('experienceYears', { type: 'number', min: 0, max: 50, value: p.experienceYears ?? 0 }),
    desiredRoles: inp('desiredRoles', { maxlength: 200, placeholder: 'e.g. Backend Developer, Data Analyst' }),
    skills: h('input', { value: (p.skills || []).join(', '), placeholder: 'Java, SQL, React, Docker' }),
    summary: h('textarea', { maxlength: 1000, value: p.summary ?? '' }),
    experience: h('textarea', { maxlength: 3000, value: p.experience ?? '' }),
    workMode: h('select', null, ['ANY', 'REMOTE', 'HYBRID', 'ONSITE'].map(v => h('option', { value: v, selected: p.workMode === v }, v))),
    jobType: h('select', null, ['ANY', 'INTERNSHIP', 'FULLTIME'].map(v => h('option', { value: v, selected: p.jobType === v }, v))),
  };
  const btn = h('button', { type: 'submit' }, 'Save profile');
  const meter = h('meter', { min: 0, max: 100, value: p.completeness.percent });
  const form = h('form', { class: 'card', novalidate: true, onsubmit: async (e) => {
    e.preventDefault(); msg.replaceChildren(); btn.disabled = true;
    try {
      const body = {
        fullName: f.fullName.value, phone: f.phone.value, location: f.location.value, institution: f.institution.value,
        degree: f.degree.value, fieldOfStudy: f.fieldOfStudy.value, gradYear: f.gradYear.value ? Number(f.gradYear.value) : null,
        experienceYears: f.experienceYears.value ? Number(f.experienceYears.value) : 0,
        desiredRoles: f.desiredRoles.value, summary: f.summary.value, experience: f.experience.value,
        workMode: f.workMode.value, jobType: f.jobType.value,
        skills: f.skills.value.split(',').map(s => s.trim()).filter(Boolean),
      };
      const saved = await api('/me/profile', { method: 'PUT', body });
      store.name = saved.fullName; meter.value = saved.completeness.percent;
      msg.replaceChildren(notice('Saved. Profile ' + saved.completeness.percent + '% complete.', 'ok'));
    } catch (err) { msg.replaceChildren(notice(err.message)); }
    finally { btn.disabled = false; }
  } },
    h('div', { class: 'grid' },
      h('label', null, 'Full name', f.fullName), h('label', null, 'Phone (stored encrypted)', f.phone),
      h('label', null, 'City', f.location), h('label', null, 'Institution', f.institution),
      h('label', null, 'Degree', f.degree), h('label', null, 'Field of study', f.fieldOfStudy),
      h('label', null, 'Graduation year', f.gradYear), h('label', null, 'Years of professional experience', f.experienceYears), h('label', null, 'Desired roles (comma separated)', f.desiredRoles),
      h('label', null, 'Preferred work mode', f.workMode), h('label', null, 'Preferred job type', f.jobType)),
    h('label', null, 'Skills (comma separated; "js" and "postgres" are recognised)', f.skills),
    h('label', null, 'Summary', f.summary),
    h('label', null, 'Experience / projects', f.experience),
    msg, h('div', { class: 'row' }, btn, h('span', { class: 'muted' }, 'Completeness'), meter));
  return h('div', null, h('h1', null, 'Your profile'),
    h('p', { class: 'muted' }, 'We only ask for what matching needs. We never ask for age, gender, photo or ID numbers.'), form);
}

// ---------- resume + ATS ----------
function atsPanel(resume) {
  const a = resume.ats;
  if (!a) return notice('No score available for this resume.');
  const sev = { HIGH: 'err', MEDIUM: 'warn' };
  return h('div', { class: 'card' },
    h('div', { class: 'row spread' },
      h('div', null, h('h2', null, 'ATS Readiness Score'), h('span', { class: 'muted' }, resume.originalName + ' · rubric v' + a.rubricVersion)),
      h('div', null, h('span', { class: 'score' }, a.total, h('small', null, ' / ' + a.maxTotal)))),
    h('meter', { min: 0, max: a.maxTotal, low: 50, high: 75, optimum: a.maxTotal, value: a.total }),
    h('p', { class: 'muted' }, a.disclaimer),
    a.warnings.map(w => notice(w.code.replace(/_/g, ' ').toLowerCase() + ': ' + w.detail, sev[w.severity] || 'warn')),
    a.breakdown.map(c => h('details', { class: 'cat', open: c.fixes.length > 0 && c.score < c.max },
      h('summary', null, h('span', null, c.category), h('span', null, c.score + ' / ' + c.max)),
      h('meter', { min: 0, max: c.max, low: c.max * .5, high: c.max * .75, optimum: c.max, value: c.score }),
      h('ul', null, c.evidence.map(e => h('li', null, e)), c.fixes.map(x => h('li', { class: 'fix' }, 'Fix: ' + x))))),
    h('h2', null, 'Skills detected in your resume'),
    a.skills.length ? chips(a.skills, 'have') : h('p', { class: 'muted' }, 'No known skills were detected. Add a Skills section.'),
    h('p', { class: 'muted' }, 'Sections found: ' + (a.sections.join(', ') || 'none') + ' · ' + a.words + ' words · ' + a.pages + ' page(s)'));
}

async function viewResume() {
  const out = h('div'); const msg = h('div'); const history = h('div');
  const show = async (id) => out.replaceChildren(atsPanel(await api('/resumes/' + id)));
  const loadHistory = async () => {
    const list = await api('/resumes');
    history.replaceChildren(list.length ? h('div', { class: 'card' }, h('h2', null, 'Your resumes'), h('table', null, h('tbody', null, list.map(r =>
      h('tr', null,
        h('td', null, r.originalName, r.active ? h('span', { class: 'badge' }, ' used for matching') : null, h('div', { class: 'muted' }, fmtDate(r.uploadedAt) + ' · score ' + r.atsTotal)),
        h('td', null, h('div', { class: 'row' },
          h('button', { class: 'ghost', onclick: () => show(r.id).catch(e => msg.replaceChildren(notice(e.message))) }, 'View'),
          r.active ? null : h('button', { class: 'ghost', onclick: async () => { await api('/resumes/' + r.id + '/activate', { method: 'PUT' }); loadHistory(); } }, 'Use for matching'),
          h('button', { class: 'ghost', onclick: async () => { if (confirm('Permanently delete this resume?')) { await api('/resumes/' + r.id, { method: 'DELETE' }); out.replaceChildren(); loadHistory(); } } }, 'Delete')))))))) : null);
    return list;
  };
  const file = h('input', { type: 'file', accept: '.pdf,.docx', 'aria-label': 'Resume file' });
  const btn = h('button', { type: 'button', onclick: async () => {
    msg.replaceChildren();
    const picked = file.files[0];
    if (!picked) return msg.replaceChildren(notice('Choose a PDF or DOCX file first.'));
    if (picked.size > 5 * 1024 * 1024) return msg.replaceChildren(notice('The file is larger than 5 MB.'));
    if (!/\.(pdf|docx)$/i.test(picked.name)) return msg.replaceChildren(notice('Only PDF and DOCX files are accepted.'));
    btn.disabled = true; btn.textContent = 'Scanning and scoring...';
    try {
      const form = new FormData(); form.append('file', picked);
      const r = await api('/resumes', { method: 'POST', form });
      if (r.duplicate) msg.replaceChildren(notice('You already uploaded this exact file. Showing its existing score.', 'warn'));
      out.replaceChildren(atsPanel(r)); await loadHistory(); file.value = ''; setTimeout(() => { location.hash = '#/recommended'; }, 800);
    } catch (e) { msg.replaceChildren(notice(e.message)); }
    finally { btn.disabled = false; btn.textContent = 'Upload and score'; }
  } }, 'Upload and score');
  const list = await loadHistory();
  if (list.length) await show((list.find(r => r.active) || list[0]).id);
  return h('div', null, h('h1', null, 'Resume and ATS score'),
    h('div', { class: 'card' },
      h('p', { class: 'muted' }, 'PDF or DOCX, up to 5 MB. Your file is virus-checked on the server, encrypted at rest, and visible only to you. The score comes from a fixed, published rubric. Nothing is guessed.'),
      h('div', { class: 'row' }, file, btn), msg),
    out, history);
}

// ---------- tracker ----------
async function viewTracker() {
  const rows = await api('/me/applications');
  const statuses = ['CLICKED', 'APPLIED', 'INTERVIEW', 'OFFER', 'REJECTED'];
  return h('div', null, h('h1', null, 'Application tracker'),
    h('p', { class: 'muted' }, 'Jobs you opened on company sites. Update the status yourself as you go. The platform cannot see what happens on the company\'s portal.'),
    rows.length ? h('div', { class: 'card' }, h('table', null, h('tbody', null, rows.map(r =>
      h('tr', null,
        h('td', null, h('strong', null, r.title), h('div', { class: 'muted' }, r.company + ' · opened ' + fmtDate(r.clickedAt))),
        h('td', null, h('select', { 'aria-label': 'Status for ' + r.title, onchange: (e) => api('/me/applications/' + r.jobId, { method: 'PATCH', body: { status: e.target.value } }).catch(err => alert(err.message)) },
          statuses.map(s => h('option', { value: s, selected: s === r.status }, s.toLowerCase())))),
        h('td', null, h('a', { href: r.applyUrl, target: '_blank', rel: 'noopener noreferrer' }, 'Open portal'))))))) : h('p', { class: 'muted' }, 'Nothing yet. Open a job\'s Apply button and it will appear here.'));
}

render();

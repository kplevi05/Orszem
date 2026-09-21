// Őrszem V2 evaluation: end-to-end acceptance against the DEPLOYED backend (through Caddy), no mocks.
//
//   node deploy/eval/acceptance-api.mjs A          # dataset EXAMPLE-1 (COMPLETE): inference, scope, workflow, admin
//   node deploy/eval/acceptance-api.mjs B          # dataset EVAL-2 (PARTIAL): partial semantics, UNCLASSIFIED, unassigned line
//   node deploy/eval/acceptance-api.mjs RATE       # rapid anonymous submissions -> 429 (run last; creates ~13 labelled test reports)
//   node deploy/eval/acceptance-api.mjs VERIFY     # read-only state check used after restarts / restores
//
// Credentials for the synthetic accounts are read from the local file named by ORSZEM_EVAL_CREDS
// (default ~/.orszem/eval/credentials.local.txt, written outside the repository by the seeding step).
import { readFileSync, writeFileSync, existsSync } from 'node:fs'
import { randomBytes, randomUUID } from 'node:crypto'
import { homedir } from 'node:os'

const BASE = process.env.ORSZEM_EVAL_BASE ?? 'http://127.0.0.1:18080'
const API = `${BASE}/api/v1`
const HOME = process.env.ORSZEM_EVAL_HOME ?? `${homedir()}/.orszem/eval`
const STATE = `${HOME}/acceptance-state.json`
const phase = process.argv[2] ?? 'A'

// ------------------------------------------------------------------------------------------- credentials
const credFile = process.env.ORSZEM_EVAL_CREDS ?? `${HOME}/credentials.local.txt`
const accounts = []
for (const line of readFileSync(credFile, 'utf8').split(/\r?\n/)) {
  const m = line.match(/^(SUPER_ADMIN|SERVICE_USER|MODERATOR)\s+(SZ-\d{6})\s+(\S+)\s+(.*)$/)
  if (m) accounts.push({ role: m[1], id: m[2], pw: m[3], scope: m[4] })
}
const acct = (role, scope) => {
  const a = accounts.find((x) => x.role === role && x.scope === scope)
  if (!a) throw new Error(`no ${role} account with scope "${scope}"`)
  return a
}
const SUPER = acct('SUPER_ADMIN', 'global')
const N_USER = acct('SERVICE_USER', 'Északi terület')
const S_USER = acct('SERVICE_USER', 'Déli terület')
const NS_USER = acct('SERVICE_USER', 'Északi + Déli terület')
const G_USER = acct('SERVICE_USER', 'globális hozzáférés')
const G_MOD = acct('MODERATOR', 'globális hozzáférés')
const N_MOD = acct('MODERATOR', 'Északi terület')

// ------------------------------------------------------------------------------------------------ plumbing
const results = []
function check(name, ok, detail = '') {
  results.push({ name, ok: !!ok, detail })
  console.log(`${ok ? 'PASS' : 'FAIL'}  ${name}${detail ? '  -- ' + detail : ''}`)
}

async function http(method, path, { token, body, headers = {} } = {}) {
  const res = await fetch(`${API}${path}`, {
    method,
    headers: { 'content-type': 'application/json', ...(token ? { authorization: `Bearer ${token}` } : {}), ...headers },
    body: body === undefined ? undefined : JSON.stringify(body),
  })
  const text = await res.text()
  let json = null
  try { json = text ? JSON.parse(text) : null } catch { /* not JSON */ }
  return { status: res.status, json, headers: res.headers }
}

const sessions = {}
async function login(a) {
  if (sessions[a.id]) return sessions[a.id]
  const r = await http('POST', '/service/auth/login', { body: { serviceId: a.id, password: a.pw } })
  if (r.status !== 200) throw new Error(`login ${a.id} -> ${r.status} ${r.json?.code}`)
  sessions[a.id] = r.json.accessToken
  return r.json.accessToken
}
const svc = async (a, method, path, body) => http(method, path, { token: await login(a), body })

// -------------------------------------------------------------------------------------------- public helpers
const credential = () => `pr_${randomBytes(32).toString('base64url')}`
async function search(q) { return (await http('GET', `/public/reference/settlements?query=${encodeURIComponent(q)}`)).json }
async function lineLookup(id) { return (await http('GET', `/public/reference/settlements/${id}/railway-lines`)).json }

async function submit({ settlementId, railwayLineId, tag, eventTypeCode }) {
  const cred = credential()
  const clientSubmissionId = randomUUID()
  const body = {
    clientSubmissionId, occurredAt: new Date(Date.now() - 60_000).toISOString(),
    trainIdentifier: tag, settlementId, railwayLineId: railwayLineId ?? null, eventTypeCode,
  }
  const r = await http('POST', '/public/reports', { body, headers: { 'X-Orszem-Report-Access': cred } })
  return { ...r, cred, body }
}
const publicStatus = async (id, cred) => (await http('GET', `/public/reports/${id}`, { headers: { 'X-Orszem-Report-Access': cred } })).json?.status

async function firstEventType() {
  const cat = (await http('GET', '/public/report-catalog')).json
  return cat.categories[0].eventTypes[0].code
}

async function findReport(a, publicReportId, lists = ['new', 'in-progress', 'archive']) {
  for (const l of lists) {
    const r = await svc(a, 'GET', `/service/reports/${l}?size=100`)
    const hit = r.json?.items?.find((x) => x.publicReportId === publicReportId)
    if (hit) return { list: l, item: hit }
  }
  return null
}
const detail = async (a, id) => svc(a, 'GET', `/service/reports/${id}`)

const areaIds = async () => {
  const r = await svc(SUPER, 'GET', '/service/service-area-admin/areas?size=100')
  const items = r.json.items ?? r.json
  return Object.fromEntries(items.map((x) => [x.name, x.id]))
}

// =================================================================================================== PHASE A
async function phaseA() {
  const ev = await firstEventType()
  const tag = `EVAL-A-${Date.now().toString(36)}`
  const [pf] = await search('Példafalva'); const [mv] = await search('Mintaváros'); const [tt] = await search('Tesztület')
  check('A1 settlement search returns fictional settlements', pf && mv && tt, `${pf?.name}, ${mv?.name}, ${tt?.name}`)
  const lp = await lineLookup(pf.id), lm = await lineLookup(mv.id), lt = await lineLookup(tt.id)
  check('A2 relations: Példafalva→[900], Mintaváros→[900,901], Tesztület→[901], coverage COMPLETE',
    lp.coverage === 'COMPLETE' && lp.items.map((x) => x.code).join() === '900' &&
    lm.items.map((x) => x.code).sort().join() === '900,901' && lt.items.map((x) => x.code).join() === '901',
    `coverage=${lp.coverage}`)
  const l900 = lm.items.find((x) => x.code === '900'), l901 = lm.items.find((x) => x.code === '901')
  const areas = await areaIds(); const NORTH = areas['Északi szolgálati terület'], SOUTH = areas['Déli szolgálati terület']

  // inferred routing (COMPLETE, exactly one verified line)
  const rInfN = await submit({ settlementId: pf.id, tag: `${tag}-inf-N`, eventTypeCode: ev })
  const rInfS = await submit({ settlementId: tt.id, tag: `${tag}-inf-S`, eventTypeCode: ev })
  // explicit selection in a two-line settlement
  const rExpN = await submit({ settlementId: mv.id, railwayLineId: l900.id, tag: `${tag}-exp-N`, eventTypeCode: ev })
  const rExpS = await submit({ settlementId: mv.id, railwayLineId: l901.id, tag: `${tag}-exp-S`, eventTypeCode: ev })
  // two candidates and no selection -> UNCLASSIFIED
  const rUnc = await submit({ settlementId: mv.id, tag: `${tag}-unc`, eventTypeCode: ev })
  check('A3 anonymous submissions accepted (201, RECEIVED)', [rInfN, rInfS, rExpN, rExpS, rUnc].every((r) => r.status === 201 && r.json.initialStatus === 'RECEIVED'),
    [rInfN, rInfS, rExpN, rExpS, rUnc].map((r) => r.status).join())
  const id = (r) => r.json.reportId
  const state = { tag, ids: { rInfN: id(rInfN), rInfS: id(rInfS), rExpN: id(rExpN), rExpS: id(rExpS), rUnc: id(rUnc) },
    creds: { rInfN: rInfN.cred, rInfS: rInfS.cred, rExpN: rExpN.cred, rExpS: rExpS.cred, rUnc: rUnc.cred }, NORTH, SOUTH }
  writeFileSync(STATE, JSON.stringify(state))

  const cls = async (k) => (await detail(SUPER, id({ rInfN, rInfS, rExpN, rExpS, rUnc }[k]))).json
  const dInfN = await cls('rInfN'), dInfS = await cls('rInfS'), dExpN = await cls('rExpN'), dExpS = await cls('rExpS'), dUnc = await cls('rUnc')
  check('A4 inferred routing (single verified line, COMPLETE): Példafalva→North, Tesztület→South',
    dInfN.serviceArea?.id === NORTH && dInfS.serviceArea?.id === SOUTH, `${dInfN.routingClassification}/${dInfN.serviceArea?.name} ; ${dInfS.routingClassification}/${dInfS.serviceArea?.name}`)
  check('A5 explicit line selection routes to different areas (900→North, 901→South)',
    dExpN.serviceArea?.id === NORTH && dExpS.serviceArea?.id === SOUTH, `${dExpN.serviceArea?.name} ; ${dExpS.serviceArea?.name}`)
  check('A6 two candidates and no selection -> UNCLASSIFIED', dUnc.routingClassification?.includes('UNCLASSIFIED') && !dUnc.serviceArea,
    `${dUnc.routingClassification} reason=${dUnc.routingReason}`)

  // scope restrictions
  const nSees = async (k) => !!(await findReport(N_USER, id({ rInfN, rInfS, rExpN, rExpS, rUnc }[k])))
  check('A7 North user sees North reports, not South, not UNCLASSIFIED',
    (await nSees('rInfN')) && (await nSees('rExpN')) && !(await nSees('rInfS')) && !(await nSees('rExpS')) && !(await nSees('rUnc')))
  const sSees = async (k) => !!(await findReport(S_USER, id({ rInfN, rInfS, rExpN, rExpS, rUnc }[k])))
  check('A8 South user sees South reports only', (await sSees('rInfS')) && (await sSees('rExpS')) && !(await sSees('rInfN')) && !(await sSees('rUnc')))
  const nsSees = async (k) => !!(await findReport(NS_USER, id({ rInfN, rInfS, rExpN, rExpS, rUnc }[k])))
  check('A9 multi-area (North+South) user sees both areas, not UNCLASSIFIED', (await nsSees('rInfN')) && (await nsSees('rInfS')) && !(await nsSees('rUnc')))
  const gmSeesUnc = !!(await findReport(G_MOD, id(rUnc))), saSeesUnc = !!(await findReport(SUPER, id(rUnc)))
  check('A10 UNCLASSIFIED visible to global MODERATOR and SUPER_ADMIN', gmSeesUnc && saSeesUnc)
  const gUserSees = { n: !!(await findReport(G_USER, id(rInfN))), s: !!(await findReport(G_USER, id(rInfS))), u: !!(await findReport(G_USER, id(rUnc))) }
  check('A11 global-access SERVICE_USER sees every routed area', gUserSees.n && gUserSees.s, `unclassified visible=${gUserSees.u}`)
  const nModSeesSouth = !!(await findReport(N_MOD, id(rInfS)))
  check('A12 territorial MODERATOR (North) does not see South', !nModSeesSouth)
  const crossDetail = await detail(N_USER, id(rInfS))
  check('A13 North user cannot open a South report (404/403)', [403, 404].includes(crossDetail.status), `status=${crossDetail.status}`)

  // workflow: claim -> IN_PROGRESS -> return -> claim -> reassign -> close -> archive, with Public status
  const target = id(rInfN)
  const pubBefore = await publicStatus(target, rInfN.cred)
  let d = (await detail(N_USER, target)).json
  const claim = await svc(N_USER, 'POST', `/service/reports/${target}/claim`, { expectedVersion: d.workflowVersion })
  d = (await detail(N_USER, target)).json
  const pubProc = await publicStatus(target, rInfN.cred)
  check('A14 Public RECEIVED -> claim -> PROCESSING', pubBefore === 'RECEIVED' && claim.status === 200 && d.status === 'IN_PROGRESS' && pubProc === 'PROCESSING', `${pubBefore} -> ${d.status}/${pubProc}`)
  const ret = await svc(N_USER, 'POST', `/service/reports/${target}/return`, { expectedVersion: d.workflowVersion })
  d = (await detail(N_USER, target)).json
  check('A15 return -> NEW / RECEIVED again', ret.status === 200 && d.status === 'NEW' && (await publicStatus(target, rInfN.cred)) === 'RECEIVED', `${ret.status} ${d.status}`)
  const claim2 = await svc(N_USER, 'POST', `/service/reports/${target}/claim`, { expectedVersion: d.workflowVersion })
  d = (await detail(N_USER, target)).json
  check('A16 claim again', claim2.status === 200 && d.status === 'IN_PROGRESS', `${claim2.status}`)
  const stale = await svc(NS_USER, 'POST', `/service/reports/${target}/claim`, { expectedVersion: 0 })
  check('A17 stale/foreign claim refused (409/4xx)', stale.status >= 400 && stale.status < 500, `status=${stale.status} ${stale.json?.code ?? ''}`)
  const reas = await svc(N_MOD, 'POST', `/service/reports/${target}/reassign`, { expectedVersion: d.workflowVersion, targetServiceId: NS_USER.id })
  d = (await detail(N_MOD, target)).json
  check('A18 territorial moderator reassigns to another user', reas.status === 200 && d.assignee?.serviceId === NS_USER.id, `${reas.status} assignee=${d.assignee?.serviceId}`)
  const close = await svc(NS_USER, 'POST', `/service/reports/${target}/close`, { expectedVersion: d.workflowVersion })
  d = (await detail(NS_USER, target)).json
  const pubClosed = await publicStatus(target, rInfN.cred)
  check('A19 close -> ARCHIVED; Public CLOSED', close.status === 200 && d.status === 'ARCHIVED' && pubClosed === 'CLOSED', `${d.status}/${pubClosed}`)
  check('A20 assignment history recorded (>=2 entries, ended with reasons)', d.assignmentHistory.length >= 2, d.assignmentHistory.map((h) => `${h.assigneeServiceId}:${h.endReason ?? 'open'}`).join(' | '))
  const inArchive = await findReport(N_USER, target, ['archive'])
  check('A21 Archive lists the closed report', !!inArchive)
  const unauthPublic = await http('GET', `/public/reports/${target}`, { headers: { 'X-Orszem-Report-Access': credential() } })
  check('A22 Public lookup with a wrong credential is refused', [401, 403, 404].includes(unauthPublic.status), `status=${unauthPublic.status}`)

  // moderation
  const delTarget = id(rExpS)
  let ds = (await detail(SUPER, delTarget)).json
  const del403 = await svc(S_USER, 'POST', `/service/moderation/reports/${delTarget}/delete`, { expectedVersion: ds.workflowVersion, reason: 'DUPLICATE' })
  check('A23 SERVICE_USER cannot moderate-delete (403)', del403.status === 403, `status=${del403.status}`)
  const del = await svc(G_MOD, 'POST', `/service/moderation/reports/${delTarget}/delete`, { expectedVersion: ds.workflowVersion, reason: 'DUPLICATE' })
  const deletedList = await svc(G_MOD, 'GET', '/service/moderation/deleted?size=100')
  check('A24 global MODERATOR deletes (204); appears in the deleted list', del.status === 204 && !!deletedList.json.items.find((x) => x.publicReportId === delTarget), `delete=${del.status}`)
  const pubDeleted = await publicStatus(delTarget, rExpS.cred)
  check('A25 Public sees a moderation-deleted report as CLOSED (by design)', pubDeleted === 'CLOSED', `public=${pubDeleted}`)
  const ds2 = await svc(G_MOD, 'GET', `/service/moderation/deleted/${delTarget}`)
  const ver = ds2.json?.workflowVersion ?? ds.workflowVersion
  const restoreMod = await svc(G_MOD, 'POST', `/service/moderation/reports/${delTarget}/restore`, { expectedVersion: ver })
  check('A26 restore is SUPER_ADMIN only: a MODERATOR is refused (403)', restoreMod.status === 403, `status=${restoreMod.status}`)
  const restore = await svc(SUPER, 'POST', `/service/moderation/reports/${delTarget}/restore`, { expectedVersion: ver })
  check('A27 SUPER_ADMIN restores; the report is back in its South queue', restore.status === 204 && !!(await findReport(S_USER, delTarget)), `restore=${restore.status}`)

  // analytics / audit / admin / user management + role restrictions
  const an = await svc(SUPER, 'GET', '/service/analytics/summary?period=LAST_30_DAYS')
  const anN = await svc(N_USER, 'GET', '/service/analytics/summary?period=LAST_30_DAYS')
  check('A28 analytics summary for SUPER_ADMIN and a territorial user', an.status === 200 && anN.status === 200, `super=${an.status} north=${anN.status}`)
  const audit = await svc(SUPER, 'GET', '/service/audit/events?size=50')
  check('A29 audit events readable by SUPER_ADMIN, with entries', audit.status === 200 && (audit.json.items?.length ?? 0) > 0, `n=${audit.json?.items?.length}`)
  const denied = await Promise.all([
    svc(N_USER, 'GET', '/service/audit/events'), svc(N_USER, 'GET', '/service/user-management/users'),
    svc(N_USER, 'GET', '/service/service-area-admin/areas'), svc(S_USER, 'POST', '/service/service-area-admin/areas', { name: 'x' }),
  ])
  check('A30 SERVICE_USER denied audit, user management and area admin (403)', denied.every((r) => r.status === 403), denied.map((r) => r.status).join())
  const temp = await svc(SUPER, 'POST', '/service/user-management/users', { role: 'SERVICE_USER', areaIds: [NORTH], globalAreaAccess: false })
  const tid = temp.json.serviceId
  const grant = await svc(SUPER, 'POST', `/service/user-management/users/${tid}/areas/${SOUTH}/grant`)
  const revoke = await svc(SUPER, 'POST', `/service/user-management/users/${tid}/areas/${SOUTH}/revoke`)
  const deact = await svc(SUPER, 'POST', `/service/user-management/users/${tid}/deactivate`)
  const loginDeact = await http('POST', '/service/auth/login', { body: { serviceId: tid, password: 'w'.repeat(16) } })
  const react = await svc(SUPER, 'POST', `/service/user-management/users/${tid}/reactivate`)
  check('A31 user management: create, grant/revoke area, deactivate, reactivate', [temp.status, grant.status, revoke.status, deact.status, react.status].every((s) => s >= 200 && s < 300) && loginDeact.status >= 400,
    [temp.status, grant.status, revoke.status, deact.status, react.status].join())
  const area = await svc(SUPER, 'POST', '/service/service-area-admin/areas', { name: `Ideiglenes terület ${Date.now().toString(36)}` })
  const rename = await svc(SUPER, 'POST', `/service/service-area-admin/areas/${area.json.id}/rename`, { expectedVersion: area.json.adminVersion, name: `Átnevezett terület ${Date.now().toString(36)}` })
  const deactA = await svc(SUPER, 'POST', `/service/service-area-admin/areas/${area.json.id}/deactivate`, { expectedVersion: rename.json.adminVersion })
  check('A32 service-area administration: create, rename, deactivate', [area.status, rename.status, deactA.status].every((s) => s >= 200 && s < 300), [area.status, rename.status, deactA.status].join())
  const me = await svc(N_USER, 'GET', '/service/account/me')
  check('A33 session identity (/me) returns role and areas', me.status === 200 && me.json.role === 'SERVICE_USER' && me.json.areas.length === 1, JSON.stringify({ role: me.json?.role, areas: me.json?.areas?.length }))
}

// =================================================================================================== PHASE B
async function phaseB() {
  const ev = await firstEventType()
  const tag = `EVAL-B-${Date.now().toString(36)}`
  const [pf] = await search('Példafalva'); const [no] = await search('Nincsvasút'); const [vl] = await search('Vonalatlan'); const [tt] = await search('Tesztület'); const [mv] = await search('Mintaváros')
  check('B1 EVAL-2 settlements present (Nincsvasút, Vonalatlan)', !!no && !!vl)
  const lp = await lineLookup(pf.id), ln = await lineLookup(no.id), lv = await lineLookup(vl.id)
  check('B2 coverage is PARTIAL now; no-relation settlement returns an empty list, not an error',
    lp.coverage === 'PARTIAL' && ln.coverage === 'PARTIAL' && ln.items.length === 0 && lv.items.map((x) => x.code).join() === '902', `coverage=${lp.coverage}`)
  const areas = await areaIds(); const NORTH = areas['Északi szolgálati terület'], SOUTH = areas['Déli szolgálati terület']
  const l900 = lp.items[0]

  const rNoSel = await submit({ settlementId: pf.id, tag: `${tag}-nosel`, eventTypeCode: ev })
  const dNoSel = (await detail(SUPER, rNoSel.json.reportId)).json
  check('B3 PARTIAL: a single verified line is NOT auto-inferred -> UNCLASSIFIED', dNoSel.routingClassification?.includes('UNCLASSIFIED') && !dNoSel.serviceArea, `${dNoSel.routingClassification} ${dNoSel.routingReason}`)
  const rSel = await submit({ settlementId: pf.id, railwayLineId: l900.id, tag: `${tag}-sel`, eventTypeCode: ev })
  const dSel = (await detail(SUPER, rSel.json.reportId)).json
  check('B4 PARTIAL: explicit selection still routes to the mapped area (North)', dSel.serviceArea?.id === NORTH, `${dSel.serviceArea?.name}`)
  const rNone = await submit({ settlementId: no.id, tag: `${tag}-none`, eventTypeCode: ev })
  const dNone = (await detail(SUPER, rNone.json.reportId)).json
  check('B5 settlement with no verified relation -> UNCLASSIFIED (no verified railway reference)', rNone.status === 201 && dNone.routingClassification?.includes('UNCLASSIFIED'), `${dNone.routingReason}`)
  const rUn = await submit({ settlementId: vl.id, railwayLineId: lv.items[0].id, tag: `${tag}-unassigned`, eventTypeCode: ev })
  const dUn = (await detail(SUPER, rUn.json.reportId)).json
  check('B6 verified line with no service-area mapping -> UNCLASSIFIED (line unassigned)', dUn.routingClassification?.includes('UNCLASSIFIED') && !dUn.serviceArea, `${dUn.routingReason}`)
  const rBad = await submit({ settlementId: tt.id, railwayLineId: l900.id, tag: `${tag}-mismatch`, eventTypeCode: ev })
  const dBad = rBad.status === 201 ? (await detail(SUPER, rBad.json.reportId)).json : null
  check('B7 an existing line NOT related to the settlement is accepted but UNCLASSIFIED / REFERENCE_MISMATCH (ADR 0007: always validated)', rBad.status === 201 && dBad.routingClassification === 'UNCLASSIFIED' && dBad.routingReason === 'REFERENCE_MISMATCH', `status=${rBad.status} ${dBad?.routingReason ?? ''}`)
  const rGhost = await submit({ settlementId: tt.id, railwayLineId: randomUUID(), tag: `${tag}-ghost`, eventTypeCode: ev })
  check('B7b a nonexistent line id is refused with 400, no report created', rGhost.status === 400, `status=${rGhost.status} ${rGhost.json?.code ?? ''}`)

  // assign 902 to South through the admin API; new reports route; the earlier snapshot must not change
  const lines = await svc(SUPER, 'GET', '/service/service-area-admin/railway-lines?size=100')
  const l902 = (lines.json.items ?? lines.json).find((x) => x.lineCode === '902')
  const asg = await svc(SUPER, 'POST', `/service/service-area-admin/railway-lines/${l902.id}/assign`, { targetServiceAreaId: SOUTH, expectedCurrentServiceAreaId: null })
  const rNow = await submit({ settlementId: vl.id, railwayLineId: lv.items[0].id, tag: `${tag}-now-south`, eventTypeCode: ev })
  const dNow = (await detail(SUPER, rNow.json.reportId)).json
  const dUnAfter = (await detail(SUPER, rUn.json.reportId)).json
  check('B8 after assigning line 902 to South: new report routes South; the earlier UNCLASSIFIED report keeps its routing snapshot',
    asg.status < 300 && dNow.serviceArea?.id === SOUTH && dUnAfter.routingClassification?.includes('UNCLASSIFIED') && !dUnAfter.serviceArea,
    `assign=${asg.status} new=${dNow.serviceArea?.name} old=${dUnAfter.routingClassification}`)
  // put the line back to unassigned so the evaluation keeps one deliberately unassigned line
  const un = await svc(SUPER, 'POST', `/service/service-area-admin/railway-lines/${l902.id}/unassign`, { expectedCurrentServiceAreaId: SOUTH })
  check('B9 unassign returns the line to the unassigned state', un.status < 300, `status=${un.status}`)
  const prev = existsSync(STATE) ? JSON.parse(readFileSync(STATE, 'utf8')) : {}
  writeFileSync(STATE, JSON.stringify({ ...prev, phaseB: { rNoSel: rNoSel.json.reportId, rSel: rSel.json.reportId, rNone: rNone.json.reportId, rUn: rUn.json.reportId }, NORTH, SOUTH }))
}

// ================================================================================================== RATE LIMIT
async function rate() {
  const ev = await firstEventType()
  const [no] = await search('Nincsvasút')
  console.log('waiting 210 s so the per-source bucket is full (capacity 10, 1 token / 20 s) ...')
  await new Promise((r) => setTimeout(r, 210_000))
  const codes = []; let retryAfter = null, errBody = null, created = 0
  for (let i = 0; i < 13; i++) {
    const r = await submit({ settlementId: no.id, tag: `EVAL-RATE-${i}`, eventTypeCode: ev })
    codes.push(r.status)
    if (r.status === 201) created++
    if (r.status === 429) { retryAfter = r.headers.get('retry-after'); errBody = r.json }
  }
  const ok201 = codes.filter((c) => c === 201).length, n429 = codes.filter((c) => c === 429).length
  check('R1 burst of 13 rapid anonymous submissions: first 10 accepted, the rest 429', ok201 === 10 && n429 === 3, codes.join(','))
  check('R2 429 carries Retry-After (whole seconds) and RATE_LIMITED without leaking details', errBody?.code === 'RATE_LIMITED' && /^\d+$/.test(retryAfter ?? ''), `retry-after=${retryAfter} keys=${Object.keys(errBody ?? {}).join()}`)
  const q = await svc(SUPER, 'GET', '/service/reports/new?size=100&query=EVAL-RATE')
  const seen = (q.json.items ?? []).filter((x) => (x.trainIdentifier ?? '').startsWith('EVAL-RATE-')).length
  check('R3 throttled requests created no report (10 stored, not 13)', seen === 10, `stored=${seen}`)
}

// ==================================================================================================== VERIFY
async function verify() {
  const st = JSON.parse(readFileSync(STATE, 'utf8'))
  const meta = await http('GET', '/meta')
  check('V1 API meta reachable', meta.status === 200 && meta.json.apiVersion === 'v1', JSON.stringify(meta.json))
  // users: every synthetic account can still authenticate
  for (const a of accounts.filter((x) => !x.scope.includes('temporary'))) {
    delete sessions[a.id]
    const r = await http('POST', '/service/auth/login', { body: { serviceId: a.id, password: a.pw } })
    check(`V2 login ${a.role} ${a.id} (${a.scope})`, r.status === 200)
  }
  // reports + routing snapshots
  for (const [k, id] of Object.entries(st.ids ?? {})) {
    const d = await detail(SUPER, id)
    check(`V3 report ${k} persisted with routing snapshot`, d.status === 200 && !!d.json.routingClassification, `${d.json?.status} ${d.json?.routingClassification} ${d.json?.serviceArea?.name ?? '-'}`)
  }
  const closed = st.ids?.rInfN
  if (closed) {
    const d = (await detail(SUPER, closed)).json
    check('V4 closed report keeps its assignment history and ARCHIVED status', d.status === 'ARCHIVED' && d.assignmentHistory.length >= 2, `history=${d.assignmentHistory.length}`)
    check('V5 Public credential still resolves the report (CLOSED)', (await publicStatus(closed, st.creds.rInfN)) === 'CLOSED')
  }
  const ref = await lineLookup((await search('Mintaváros'))[0].id)
  check('V6 reference data present after restart', ref.items.length === 2, `coverage=${ref.coverage} lines=${ref.items.map((x) => x.code).join()}`)
  const audit = await svc(SUPER, 'GET', '/service/audit/events?size=5')
  check('V7 audit trail readable', audit.status === 200 && audit.json.items.length > 0)
}

const runners = { A: phaseA, B: phaseB, RATE: rate, VERIFY: verify }
if (!runners[phase]) throw new Error(`unknown phase ${phase}`)
try { await runners[phase]() } catch (e) { check(`phase ${phase} aborted`, false, e.message) }
const failed = results.filter((r) => !r.ok)
console.log(`\nphase ${phase}: ${results.length - failed.length}/${results.length} checks passed`)
process.exit(failed.length ? 1 : 0)

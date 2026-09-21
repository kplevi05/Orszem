// Őrszem V2 evaluation environment: bootstrap synthetic Service state through the REAL HTTP API.
//
//   ORSZEM_EVAL_BASE=http://127.0.0.1:18080 ORSZEM_SEED_ID=SZ-… ORSZEM_SEED_TEMP=… node deploy/eval/seed-eval.mjs
//
// Takes the SUPER_ADMIN created by `eval.sh cli create-super-admin` (its one-time credential comes from
// the environment, never from a file or a command argument), completes that account's forced password
// change with a generated password, then creates two service areas, maps the fictional railway lines to
// them, and creates users covering territorial, multi-area and global scope. Generated passwords are
// printed ONCE to stdout; the script writes them to no file. Synthetic data only; nothing here is committed
// with a credential.
import { randomBytes } from 'node:crypto'

const BASE = process.env.ORSZEM_EVAL_BASE ?? 'http://127.0.0.1:18080'
const API = `${BASE}/api/v1`
const seedId = process.env.ORSZEM_SEED_ID
const seedTemp = process.env.ORSZEM_SEED_TEMP
if (!seedId || !seedTemp) throw new Error('ORSZEM_SEED_ID and ORSZEM_SEED_TEMP are required')

const newPassword = () => `Ev-${randomBytes(12).toString('base64url')}-${randomBytes(3).toString('hex')}`

async function call(method, path, { token, body, expect = [200, 201, 204] } = {}) {
  const res = await fetch(`${API}${path}`, {
    method,
    headers: { 'content-type': 'application/json', ...(token ? { authorization: `Bearer ${token}` } : {}) },
    body: body === undefined ? undefined : JSON.stringify(body),
  })
  const text = await res.text()
  let json = null
  try { json = text ? JSON.parse(text) : null } catch { /* not JSON */ }
  if (!expect.includes(res.status)) {
    throw new Error(`${method} ${path} -> ${res.status} ${json?.code ?? ''} ${json?.message ?? text.slice(0, 200)}`)
  }
  return json
}

async function completeChange(serviceId, temporaryPassword, password) {
  await call('POST', '/service/auth/complete-password-change', {
    body: { serviceId, temporaryPassword, newPassword: password },
  })
  const tokens = await call('POST', '/service/auth/login', { body: { serviceId, password } })
  return tokens.accessToken
}

const out = []
const record = (role, scope, serviceId, secret) => out.push({ role, scope, serviceId, secret })

// --- SUPER_ADMIN ---------------------------------------------------------------------------------------
const adminPassword = newPassword()
const admin = await completeChange(seedId, seedTemp, adminPassword)
record('SUPER_ADMIN', 'global', seedId, adminPassword)

// --- service areas and line mappings -------------------------------------------------------------------
const north = await call('POST', '/service/service-area-admin/areas', { token: admin, body: { name: 'Északi szolgálati terület' } })
const south = await call('POST', '/service/service-area-admin/areas', { token: admin, body: { name: 'Déli szolgálati terület' } })
const lines = await call('GET', '/service/service-area-admin/railway-lines?size=100', { token: admin })
const byCode = Object.fromEntries((lines.items ?? lines).map((l) => [l.lineCode, l]))
for (const [code, area] of [['900', north], ['901', south]]) {
  if (!byCode[code]) throw new Error(`railway line ${code} is not imported yet: import the fictional dataset first`)
  await call('POST', `/service/service-area-admin/railway-lines/${byCode[code].id}/assign`, {
    token: admin, body: { targetServiceAreaId: area.id, expectedCurrentServiceAreaId: null },
  })
}

// --- users ---------------------------------------------------------------------------------------------
async function makeUser(role, scope, { areaIds = [], global = false, keepTemporary = false } = {}) {
  const created = await call('POST', '/service/user-management/users', {
    token: admin, body: { role, areaIds, globalAreaAccess: global },
  })
  if (keepTemporary) {
    record(role, `${scope} (temporary credential, forced change on first sign-in)`, created.serviceId, created.temporaryCredential)
    return
  }
  const pw = newPassword()
  await completeChange(created.serviceId, created.temporaryCredential, pw)
  record(role, scope, created.serviceId, pw)
}

await makeUser('SERVICE_USER', 'Északi terület', { areaIds: [north.id] })
await makeUser('SERVICE_USER', 'Déli terület', { areaIds: [south.id] })
await makeUser('SERVICE_USER', 'Északi + Déli terület', { areaIds: [north.id, south.id] })
await makeUser('SERVICE_USER', 'globális hozzáférés', { global: true })
await makeUser('MODERATOR', 'globális hozzáférés', { global: true })
await makeUser('MODERATOR', 'Északi terület', { areaIds: [north.id] })
await makeUser('SERVICE_USER', 'Északi terület', { areaIds: [north.id], keepTemporary: true })

console.log(JSON.stringify({ areas: { north: north.id, south: south.id }, note: 'passwords below are printed once by this script; keep them out of Git and documentation' }))
for (const r of out) console.log(`${r.role.padEnd(12)} ${r.serviceId}  ${r.secret}   ${r.scope}`)

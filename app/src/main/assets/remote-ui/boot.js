// Shell of the website screens inside the app's WebView. The signed module (/ui/<version>/ui.js) is served by the
// app from its verified copy; this file only builds the bridge object the module expects (LauncherBridge v1 of
// millida-web, src/launcher-ui/bridge.ts) on top of one message channel to Kotlin: request and answer by id.
const params = new URLSearchParams(location.search)
const screen = params.get('screen') || 'catalog'
const version = params.get('v') || ''
const accent = params.get('accent') || ''
const host = globalThis.MillidaHost

const tell = (op, args) => {
  try {
    host.postMessage(JSON.stringify({ op, args }))
  } catch (_) {
    /* the host is gone: nothing to tell */
  }
}

let failed = false
const fail = (why) => {
  if (failed) return
  failed = true
  tell('fail', { why: String(why && (why.stack || why.message) || why).slice(0, 500) })
}

addEventListener('error', (e) => fail(e.error || e.message))
addEventListener('unhandledrejection', (e) => fail(e.reason))

class LauncherApiError extends Error {
  constructor(status, body, message) {
    super(message || 'API ' + status)
    this.name = 'LauncherApiError'
    this.status = status
    this.body = body
  }
}

const pending = new Map()
let seq = 0

host.onmessage = (e) => {
  let m
  try {
    m = JSON.parse(e.data)
  } catch (_) {
    return
  }
  const p = pending.get(m.id)
  if (!p) return
  pending.delete(m.id)
  if (m.ok) p.resolve(m.data)
  else if (m.error) p.reject(new LauncherApiError(m.error.status || 0, m.error.body ?? null, m.error.message))
  else p.reject(new LauncherApiError(0, null, 'no answer'))
}

const call = (op, args) =>
  new Promise((resolve, reject) => {
    const id = ++seq
    pending.set(id, { resolve, reject })
    host.postMessage(JSON.stringify({ id, op, args }))
  })

const bridge = {
  v: 1,
  locale: 'ru',
  platform: 'android',
  siteOrigin: 'https://millida.net',
  api: (path, init) => call('api', { path, method: init && init.method, body: init && init.body }),
  navigate: (path) => tell('navigate', { path: String(path) }),
  install: (item, target) => call('install', { item, target: target || null }),
  join: (server) => call('join', { server }),
  profiles: () => call('profiles', {}),
  openExternal: (url) => tell('openExternal', { url: String(url) }),
  toast: (message, kind) => tell('toast', { message: String(message).slice(0, 300), kind: kind || 'info' }),
  // upload is absent on purpose: the module treats a launcher without it as an old one.
}

function css(href) {
  const link = document.createElement('link')
  link.rel = 'stylesheet'
  link.href = href
  document.head.appendChild(link)
}

async function start() {
  if (accent && /^#[0-9a-f]{6}$/i.test(accent)) {
    const n = parseInt(accent.slice(1), 16)
    const root = document.documentElement.style
    root.setProperty('--host-m-accent', accent)
    root.setProperty('--host-m-accent-rgb', `${(n >> 16) & 255}, ${(n >> 8) & 255}, ${n & 255}`)
  }
  const base = '/ui/' + version + '/'
  css(base + 'ui.css')
  css(base + 'ui-ext.css')
  const ui = await import(base + 'ui.js')
  if (ui.UI_API !== 1 || typeof ui.mount !== 'function') throw new Error('remote ui api mismatch')
  const el = document.getElementById('root')
  try {
    ui.mount(el, bridge, {
      screen,
      onReady: () => requestAnimationFrame(() => tell('ready', {})),
    })
  } catch (e) {
    // The module started and fell over: the app never takes this version again.
    tell('crash', { why: String((e && (e.stack || e.message)) || e).slice(0, 500) })
  }
}

start().catch(fail)

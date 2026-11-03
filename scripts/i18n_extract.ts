import { existsSync, mkdirSync, readFileSync, readdirSync, statSync, writeFileSync } from 'node:fs'
import { join, relative } from 'node:path'

const ROOT = join(import.meta.dir, '..')
const MODULES = ['app', 'core', 'runtime']
const ASSETS = join(ROOT, 'app', 'src', 'main', 'assets', 'i18n')
const KEYS_FILE = join(ROOT, 'scripts', 'i18n_keys.json')
const MISSING_DIR = join(ROOT, 'scripts', 'i18n_missing')
const LANGUAGES = ['en', 'de', 'es', 'pl', 'pt-BR', 'tr', 'zh', 'hi', 'ar', 'fr', 'bn', 'id', 'ur', 'ja', 'vi', 'te', 'ko', 'uk', 'sr', 'sk']
const DESKTOP = process.argv.includes('--desktop')
  ? process.argv[process.argv.indexOf('--desktop') + 1]!
  : join(ROOT, '..', 'millida-launcher', 'src', 'i18n', 'locales')
const CHECK = process.argv.includes('--check')

const CALL = /(?<![\w.])(?:tr|trKey)\(\s*(?:"""([\s\S]*?)"""|"((?:[^"\\\n]|\\.)*)")/g
const PLACEHOLDER = /\{(\w+)\}/g
const TEMPLATE = /(?<!\\)\$(?:\{|[A-Za-z_])/

type Table = Record<string, string>

function* sources(dir: string): Generator<string> {
  if (!existsSync(dir)) return
  for (const name of readdirSync(dir)) {
    const path = join(dir, name)
    if (statSync(path).isDirectory()) yield* sources(path)
    else if (name.endsWith('.kt')) yield path
  }
}

const ESCAPES: Record<string, string> = { n: '\n', t: '\t', r: '\r', b: '\b', '"': '"', "'": "'", '\\': '\\', $: '$' }

export function unescapeKotlin(raw: string): string {
  return raw.replace(/\\(u[0-9a-fA-F]{4}|.)/g, (_, c: string) => (c.length === 5 ? String.fromCharCode(parseInt(c.slice(1), 16)) : ESCAPES[c] ?? c))
}

export function extractKeys(): Map<string, string> {
  const keys = new Map<string, string>()
  const broken: string[] = []
  for (const mod of MODULES) {
    for (const file of sources(join(ROOT, mod, 'src', 'main'))) {
      const text = readFileSync(file, 'utf8')
      for (const m of text.matchAll(CALL)) {
        const raw = m[1] ?? m[2] ?? ''
        const at = relative(ROOT, file).replaceAll('\\', '/') + ':' + (text.slice(0, m.index).split('\n').length)
        if (TEMPLATE.test(raw)) {
          broken.push(at + ': ' + raw)
          continue
        }
        const key = m[1] !== undefined ? raw : unescapeKotlin(raw)
        if (!key.trim()) continue
        if (!keys.has(key)) keys.set(key, at)
      }
    }
  }
  if (broken.length) {
    console.error('A key must not be a Kotlin template, its text would change with every value. Use {name} slots:\n' + broken.join('\n'))
    process.exit(1)
  }
  return keys
}

const placeholders = (text: string) => Array.from(text.matchAll(PLACEHOLDER), (m) => m[0]).sort()
const shape = (text: string) => text.replace(PLACEHOLDER, '{}')
const slotNames = (text: string) => [...new Set(Array.from(text.matchAll(PLACEHOLDER), (m) => m[1]!))]
const sameSlots = (key: string, value: string) => JSON.stringify(placeholders(key)) === JSON.stringify(placeholders(value))

const readTable = (path: string): Table => (existsSync(path) ? (JSON.parse(readFileSync(path, 'utf8')) as Table) : {})

const readList = (path: string): string[] => (existsSync(path) ? (JSON.parse(readFileSync(path, 'utf8')) as string[]) : [])

const byCodePoint = (a: string, b: string) => (a < b ? -1 : a > b ? 1 : 0)

function sorted(table: Table): Table {
  const out: Table = {}
  for (const key of Object.keys(table).sort(byCodePoint)) out[key] = table[key]!
  return out
}

/** The desktop names a slot after its own variable; the same sentence on the phone may call it otherwise. */
function desktopIndex(desktop: Table): Map<string, string[]> {
  const index = new Map<string, string[]>()
  for (const key of Object.keys(desktop)) {
    if (!PLACEHOLDER.test(key)) continue
    PLACEHOLDER.lastIndex = 0
    const s = shape(key)
    index.set(s, [...(index.get(s) ?? []), key])
  }
  return index
}

function fromDesktop(key: string, desktop: Table, index: Map<string, string[]>): string | undefined {
  const exact = desktop[key]
  if (exact) return exact
  const names = slotNames(key)
  if (!names.length) return undefined
  const candidates = (index.get(shape(key)) ?? []).filter((k) => desktop[k])
  if (candidates.length !== 1) return undefined
  const source = candidates[0]!
  const theirs = Array.from(source.matchAll(PLACEHOLDER), (m) => m[1]!)
  const ours = Array.from(key.matchAll(PLACEHOLDER), (m) => m[1]!)
  if (theirs.length !== ours.length) return undefined
  const rename = new Map<string, string>()
  for (let i = 0; i < theirs.length; i++) {
    const known = rename.get(theirs[i]!)
    if (known !== undefined && known !== ours[i]) return undefined
    rename.set(theirs[i]!, ours[i]!)
  }
  const value = desktop[source]!.replace(PLACEHOLDER, (m, name: string) => (rename.has(name) ? '{' + rename.get(name) + '}' : m))
  return sameSlots(key, value) ? value : undefined
}

function main() {
  const keys = extractKeys()
  const list = [...keys.keys()].sort(byCodePoint)
  const problems: string[] = []
  const summary: string[] = []
  if (!CHECK) writeFileSync(KEYS_FILE, JSON.stringify(list, null, 2) + '\n', 'utf8')
  else if (JSON.stringify(readList(KEYS_FILE)) !== JSON.stringify(list)) problems.push('scripts/i18n_keys.json is stale: run bun scripts/i18n_extract.ts')
  mkdirSync(ASSETS, { recursive: true })
  mkdirSync(MISSING_DIR, { recursive: true })
  for (const lang of LANGUAGES) {
    const path = join(ASSETS, lang + '.json')
    const own = readTable(path)
    const desktop = readTable(join(DESKTOP, lang + '.json'))
    const index = desktopIndex(desktop)
    const table: Table = {}
    let seeded = 0
    for (const key of list) {
      const mine = own[key]
      if (mine) {
        if (!sameSlots(key, mine)) problems.push(`${lang}.json: "${key}" -> "${mine}" lost or renamed a {slot}`)
        table[key] = mine
        continue
      }
      const theirs = CHECK ? undefined : fromDesktop(key, desktop, index)
      if (theirs) {
        table[key] = theirs
        seeded++
      }
    }
    const missing = list.filter((k) => !table[k])
    summary.push(`${lang}: ${Object.keys(table).length}/${list.length} translated, ${seeded} new from the desktop, ${missing.length} missing`)
    if (!CHECK) {
      writeFileSync(path, JSON.stringify(sorted(table), null, 2) + '\n', 'utf8')
      writeFileSync(join(MISSING_DIR, lang + '.json'), JSON.stringify(missing, null, 2) + '\n', 'utf8')
    }
  }
  console.log(`${list.length} keys -> ${relative(ROOT, KEYS_FILE)}`)
  for (const line of summary) console.log(line)
  if (problems.length) {
    console.error(problems.join('\n'))
    process.exit(1)
  }
}

if (import.meta.main) main()

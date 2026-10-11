import { writeFileSync } from 'node:fs'
import { resolve } from 'node:path'
import { PX_COLORS, PX_NAMES, pxRects, pxSize } from '../../millida-launcher/src/components/pxArt'

const OUTPUT = resolve(import.meta.dir, '../app/src/main/kotlin/net/millida/launcher/ui/PxArt.kt')
const TINT = 'c'
const TINT_SOFT = 'C'
const EMPTY = '.'
const RESERVED = new Set(['palette', 'PALETTE_KEYS', 'byId'])
const KOTLIN_KEYWORDS = new Set([
  'as', 'break', 'class', 'continue', 'do', 'else', 'false', 'for', 'fun', 'if', 'in', 'interface', 'is', 'null',
  'object', 'package', 'return', 'super', 'this', 'throw', 'true', 'try', 'typealias', 'typeof', 'val', 'var', 'when', 'while',
])

const paletteKeys = Object.keys(PX_COLORS).filter((key) => key !== TINT && key !== TINT_SOFT)

const letterByFill = new Map<string, string>()
for (const key of paletteKeys) {
  const fill = PX_COLORS[key].toLowerCase()
  if (!/^#[0-9a-f]{6}$/.test(fill)) throw new Error(`palette letter ${key} has unsupported fill ${fill}`)
  if (letterByFill.has(fill)) throw new Error(`palette letters ${letterByFill.get(fill)} and ${key} share ${fill}`)
  letterByFill.set(fill, key)
}

function letterOf(fill: string, op: number | undefined): string {
  if (fill === 'currentColor') return op === undefined ? TINT : TINT_SOFT
  const letter = letterByFill.get(fill.toLowerCase())
  if (!letter) throw new Error(`fill ${fill} is not in the palette`)
  return letter
}

function cellsOf(name: string): string {
  const { w, h } = pxSize(name)
  if (w !== h) throw new Error(`${name} is ${w}x${h}; the Kotlin side expects square glyphs`)
  const grid = Array.from({ length: h }, () => Array.from({ length: w }, () => EMPTY))
  for (const rect of pxRects(name)) {
    const letter = letterOf(rect.fill, rect.op)
    for (let x = rect.x; x < rect.x + rect.w; x++) {
      if (grid[rect.y][x] !== EMPTY) throw new Error(`${name}: overlapping cell at ${x},${rect.y}`)
      grid[rect.y][x] = letter
    }
  }
  return grid.map((row) => row.join('')).join('')
}

function identifierOf(name: string): string {
  const parts = name.split('-').filter(Boolean)
  const camel = parts.map((part, index) => (index === 0 ? part : part[0].toUpperCase() + part.slice(1))).join('')
  if (!/^[a-z][A-Za-z0-9]*$/.test(camel)) throw new Error(`${name} does not map to a Kotlin identifier (${camel})`)
  if (KOTLIN_KEYWORDS.has(camel) || RESERVED.has(camel)) throw new Error(`${name} maps to a reserved identifier ${camel}`)
  return camel
}

function runsOf(cells: string): string[] {
  const side = Math.round(Math.sqrt(cells.length))
  const runs: string[] = []
  for (let y = 0; y < side; y++) {
    let x = 0
    while (x < side) {
      const key = cells[y * side + x]
      if (key === EMPTY) {
        x++
        continue
      }
      let end = x + 1
      while (end < side && cells[y * side + end] === key) end++
      runs.push([x, y, end - x, key].join(','))
      x = end
    }
  }
  return runs
}

function assertParity(name: string, cells: string): number {
  const expected = pxRects(name).map((rect) => [rect.x, rect.y, rect.w, letterOf(rect.fill, rect.op)].join(','))
  const actual = runsOf(cells)
  if (actual.join('|') !== expected.join('|')) throw new Error(`${name}: decoded runs differ from the desktop rects`)
  return actual.length
}

const glyphs = PX_NAMES.map((name) => ({ name, id: identifierOf(name), cells: cellsOf(name) }))
const runCount = glyphs.reduce((sum, glyph) => sum + assertParity(glyph.name, glyph.cells), 0)
const seen = new Map<string, string>()
for (const glyph of glyphs) {
  const clash = seen.get(glyph.id)
  if (clash) throw new Error(`${clash} and ${glyph.name} both map to ${glyph.id}`)
  seen.set(glyph.id, glyph.name)
}

const hex = (fill: string) => '0xFF' + fill.slice(1).toUpperCase()
const lines: string[] = [
  'package net.millida.launcher.ui',
  '',
  'import androidx.compose.ui.graphics.Color',
  '',
  'internal object PxArt {',
  `    const val PALETTE_KEYS: String = "${paletteKeys.join('')}"`,
  '',
  '    val palette: List<Color> = listOf(',
  ...paletteKeys.map((key) => `        Color(${hex(PX_COLORS[key])}),`),
  '    )',
  '',
  ...glyphs.map((glyph) => `    val ${glyph.id}: String = "${glyph.cells}"`),
  '',
  '    val byId: Map<String, String> = mapOf(',
  ...glyphs.map((glyph) => `        "${glyph.name}" to ${glyph.id},`),
  '    )',
  '}',
  '',
]

writeFileSync(OUTPUT, lines.join('\n'))
console.log(`PxArt.kt: ${glyphs.length} glyphs, ${runCount} runs, ${paletteKeys.length} palette colors, ${Buffer.byteLength(lines.join('\n'))} bytes`)

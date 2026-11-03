import { mkdir, writeFile } from 'node:fs/promises'
import { dirname, join, resolve } from 'node:path'
import { createRequire } from 'node:module'

const mobile = resolve(import.meta.dir, '..')
const desktop = resolve(mobile, '..', 'millida-launcher')
const sharp = createRequire(join(mobile, '..', 'package.json'))('sharp')

const res = join(mobile, 'app', 'src', 'main', 'res')
const assets = join(mobile, 'app', 'src', 'main', 'assets')

const ALT_ICONS = ['spark', 'flame', 'amethyst', 'legend', 'gold', 'diamond']
const DENSITIES: [string, number][] = [
  ['xhdpi', 216],
  ['xxhdpi', 324],
  ['xxxhdpi', 432],
]
const ART_SHARE = 0.95

const STILLS: [string, string, string][] = [
  ['bg5', 'lobby/world.webp', 'bg/bg5.jpg'],
  ['bg2', 'bg/bg2-wide.webp', 'bg/bg2.jpg'],
  ['bg3', 'bg/bg3-wide.webp', 'bg/bg3.jpg'],
  ['bg4', 'bg/bg4-wide.webp', 'bg/bg4.jpg'],
]
const STILL_WIDTH = 1920

async function save(path: string, data: Buffer) {
  await mkdir(dirname(path), { recursive: true })
  await writeFile(path, data)
  console.log(path.slice(mobile.length + 1), data.length)
}

async function iconLayer(id: string, side: number): Promise<Buffer> {
  const source = join(desktop, 'src-tauri', 'icons', 'alt', id, '512x512.png')
  const trimmed = await sharp(source).trim().png().toBuffer()
  const { dominant } = await sharp(trimmed).stats()
  const art = Math.round(side * ART_SHARE)
  const scaled = await sharp(trimmed).resize(art, art, { fit: 'fill' }).png().toBuffer()
  const inset = Math.round((side - art) / 2)
  return sharp({ create: { width: side, height: side, channels: 3, background: dominant } })
    .composite([{ input: scaled, left: inset, top: inset }])
    .webp({ quality: 88 })
    .toBuffer()
}

function adaptiveXml(id: string): string {
  return [
    '<?xml version="1.0" encoding="utf-8"?>',
    '<adaptive-icon xmlns:android="http://schemas.android.com/apk/res/android">',
    `    <background android:drawable="@mipmap/ic_launcher_${id}_layer" />`,
    '    <foreground android:drawable="@android:color/transparent" />',
    '    <monochrome android:drawable="@drawable/ic_launcher_glyph" />',
    '</adaptive-icon>',
    '',
  ].join('\n')
}

for (const id of ALT_ICONS) {
  for (const [density, side] of DENSITIES) {
    await save(join(res, `mipmap-${density}`, `ic_launcher_${id}_layer.webp`), await iconLayer(id, side))
  }
  await save(join(res, 'mipmap-anydpi-v26', `ic_launcher_${id}.xml`), Buffer.from(adaptiveXml(id)))
  const preview = await sharp(join(desktop, 'public', 'app-icons', `${id}.png`)).resize(160, 160).webp({ quality: 85 }).toBuffer()
  await save(join(assets, 'app-icons', `${id}.webp`), preview)
}
const defaultPreview = await sharp(join(res, 'mipmap-xxxhdpi', 'ic_launcher.png')).resize(160, 160).webp({ quality: 90 }).toBuffer()
await save(join(assets, 'app-icons', 'default.webp'), defaultPreview)

for (const [id, wide, thumb] of STILLS) {
  const full = await sharp(join(desktop, 'public', wide)).resize({ width: STILL_WIDTH, withoutEnlargement: true }).webp({ quality: 80 }).toBuffer()
  await save(join(assets, 'wallpaper-stills', `${id}.webp`), full)
  const small = await sharp(join(desktop, 'public', thumb)).resize(320, 180).webp({ quality: 75 }).toBuffer()
  await save(join(assets, 'wallpaper-stills', `${id}-thumb.webp`), small)
}

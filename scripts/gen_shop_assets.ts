import { copyFile, mkdir, readdir, stat, writeFile } from 'node:fs/promises'
import { dirname, join, resolve } from 'node:path'
import { createRequire } from 'node:module'

const mobile = resolve(import.meta.dir, '..')
const desktop = resolve(mobile, '..', 'millida-launcher')
const sharp = createRequire(join(mobile, '..', 'package.json'))('sharp')
const assets = join(mobile, 'app', 'src', 'main', 'assets')

async function save(path: string, data: Buffer) {
  await mkdir(dirname(path), { recursive: true })
  await writeFile(path, data)
  console.log(path.slice(mobile.length + 1), data.length)
}

async function chestModels() {
  const from = join(desktop, 'src', 'components', 'daily', 'chests')
  for (const name of await readdir(from)) {
    if (!name.endsWith('.bbmodel')) continue
    const to = join(assets, 'chests', name)
    await mkdir(dirname(to), { recursive: true })
    await copyFile(join(from, name), to)
    console.log(to.slice(mobile.length + 1), (await stat(to)).size)
  }
}

async function lossless(dir: string, out: string) {
  const from = join(desktop, 'public', dir)
  for (const name of await readdir(from)) {
    if (!name.endsWith('.png')) continue
    const data = await sharp(join(from, name)).webp({ lossless: true, effort: 6 }).toBuffer()
    await save(join(assets, out, name.replace(/\.png$/, '.webp')), data)
  }
}

async function currency() {
  const from = join(desktop, 'public', 'currency')
  for (const name of await readdir(from)) {
    if (!name.endsWith('.png') || /-(48|96)\.png$/.test(name)) continue
    const data = await sharp(join(from, name)).webp({ lossless: true, effort: 6 }).toBuffer()
    await save(join(assets, 'currency', name.replace(/\.png$/, '.webp')), data)
  }
}

async function plusArt() {
  for (const name of ['hero-gold.webp', 'scene.webp']) {
    const to = join(assets, 'plus', name)
    await mkdir(dirname(to), { recursive: true })
    await copyFile(join(desktop, 'public', 'plus', name), to)
    console.log(to.slice(mobile.length + 1), (await stat(to)).size)
  }
  const badge = await sharp(join(desktop, 'public', 'plus', 'badge-plus.png')).webp({ lossless: true }).toBuffer()
  await save(join(assets, 'plus', 'badge-plus.webp'), badge)
}

async function main() {
  await chestModels()
  await lossless('chests', 'chests')
  await currency()
  await lossless('packs', 'packs')
  await plusArt()
}

await main()

import { chromium } from 'playwright'
import { stat } from 'node:fs/promises'
import { fileURLToPath, pathToFileURL } from 'node:url'
import { dirname, join, resolve } from 'node:path'

const projectRoot = dirname(dirname(dirname(fileURLToPath(import.meta.url))))
const videoFile = process.env.VIDEO_FILE ? resolve(process.env.VIDEO_FILE) : join(projectRoot, 'demo', 'wavenest-complete-demo.mp4')
const info = await stat(videoFile)
const browser = await chromium.launch({ headless: true, ...(process.env.BROWSER_PATH ? { executablePath: process.env.BROWSER_PATH } : {}) })
const page = await browser.newPage({ viewport: { width: 1440, height: 900 } })
await page.goto(pathToFileURL(videoFile).href)
const video = page.locator('video')
await video.waitFor()
await video.evaluate(element => {
  element.controls = false
  document.body.style.margin = '0'
  element.style.width = '1440px'
  element.style.height = '900px'
})
await page.waitForFunction(() => document.querySelector('video')?.readyState >= 1)
const metadata = await video.evaluate(element => ({ duration: element.duration, width: element.videoWidth, height: element.videoHeight }))
if (metadata.duration < 80 || metadata.width !== 1440 || metadata.height !== 900 || info.size < 1000000) throw new Error(`video incomplete: ${JSON.stringify(metadata)}, ${info.size} bytes`)
for (const [label, second] of [['opening', 3], ['branch', metadata.duration * .48], ['closing', metadata.duration - 5]]) {
  await video.evaluate((element, time) => { element.currentTime = time }, second)
  await page.waitForFunction(time => Math.abs(document.querySelector('video').currentTime - time) < .3 && document.querySelector('video').readyState >= 2, second)
  await page.screenshot({ path: join(projectRoot, 'demo', `video-${label}.png`) })
}
console.log(JSON.stringify({ ...metadata, bytes: info.size, file: videoFile }))
await browser.close()

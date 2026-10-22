#!/usr/bin/env node
/**
 * Neemt een login + catalogus + afspeelpoging op in de echte webapp en schrijft
 * een geschoonde HAR. Dit is de "waarheid" waartegen de fix-agent de plugin vergelijkt.
 *
 *   node capture.mjs <plugin-id> <uitvoer.har>
 *
 * Credentials via env <PLUGIN>_USERNAME / <PLUGIN>_PASSWORD (bv. VRTMAX_USERNAME).
 * Gebruik CHROME_CHANNEL=chrome als Google Chrome op de runner staat: enkel Chrome
 * heeft Widevine, zodat ook de licentie-request in de opname zit.
 *
 * Selectors zijn bewust ruim en met fallbacks: als de loginpagina wijzigt,
 * eindigt de opname alsnog met wat er tot dan toe gebeurd is.
 */
import { chromium } from "playwright";
import { mkdtempSync, rmSync } from "node:fs";
import { tmpdir } from "node:os";
import { join } from "node:path";
import { sanitizeHarFile } from "./sanitize.mjs";

const flows = {
  vrtmax: {
    start: "https://www.vrt.be/vrtmax/",
    async login(page, user, pass) {
      await clickFirst(page, ["button:has-text('Alles accepteren')", "#didomi-notice-agree-button", "button:has-text('Accepteer')"]);
      await clickFirst(page, ["a:has-text('Aanmelden')", "button:has-text('Aanmelden')", "a[href*='sso/login']"]);
      await page.waitForURL(/login\.vrt\.be/, { timeout: 20000 }).catch(() => {});
      await fillFirst(page, ["input[type=email]", "input[name=loginID]", "#email"], user);
      await clickFirst(page, ["button:has-text('Doorgaan')", "button:has-text('Volgende')"]);
      await fillFirst(page, ["input[type=password]", "#password"], pass);
      await clickFirst(page, ["button[type=submit]", "button:has-text('Aanmelden')"]);
      await page.waitForURL(/vrt\.be\/vrtmax/, { timeout: 30000 }).catch(() => {});
    },
    async browseAndPlay(page) {
      await page.goto("https://www.vrt.be/vrtmax/a-z/", { waitUntil: "networkidle" }).catch(() => {});
      await clickFirst(page, ["a[href*='/vrtmax/a-z/']:not([href$='/a-z/'])"]);
      await page.waitForLoadState("networkidle").catch(() => {});
      await clickFirst(page, ["a[href*='/vrtmax/a-z/'][href*='/1/']", "button:has-text('Afspelen')", "a:has-text('Kijk')"]);
      await page.waitForTimeout(20000); // tokens, media-items, manifest, licentie
    },
  },
  play: {
    start: "https://www.play.tv/",
    async login(page, user, pass) {
      await clickFirst(page, ["#didomi-notice-agree-button", "button:has-text('Alles accepteren')", "button:has-text('Akkoord')"]);
      await clickFirst(page, ["a:has-text('Aanmelden')", "button:has-text('Aanmelden')", "a[href*='login']"]);
      await fillFirst(page, ["input[type=email]", "input[name=email]", "#email"], user);
      await fillFirst(page, ["input[type=password]", "#password"], pass);
      await clickFirst(page, ["button[type=submit]", "button:has-text('Aanmelden')", "button:has-text('Inloggen')"]);
      await page.waitForTimeout(8000); // Cognito InitiateAuth + RespondToAuthChallenge
    },
    async browseAndPlay(page) {
      await page.goto("https://www.play.tv/programmas", { waitUntil: "networkidle" }).catch(() => {});
      await clickFirst(page, ["a[href*='/programmas/']:not([href$='/programmas'])"]);
      await page.waitForLoadState("networkidle").catch(() => {});
      await clickFirst(page, ["button:has-text('Kijk')", "a:has-text('Kijk')", "button:has-text('Afspelen')"]);
      await page.waitForTimeout(20000); // api.play.tv videos, manifest, KeyOS-licentie
    },
  },
};

async function clickFirst(page, selectors) {
  for (const s of selectors) {
    const el = page.locator(s).first();
    if (await el.isVisible({ timeout: 3000 }).catch(() => false)) {
      await el.click().catch(() => {});
      return true;
    }
  }
  return false;
}

async function fillFirst(page, selectors, value) {
  for (const s of selectors) {
    const el = page.locator(s).first();
    if (await el.isVisible({ timeout: 5000 }).catch(() => false)) {
      await el.fill(value);
      return true;
    }
  }
  return false;
}

const [, , pluginId, outFile] = process.argv;
const flow = flows[pluginId];
if (!flow || !outFile) {
  console.error(`gebruik: node capture.mjs <${Object.keys(flows).join("|")}> <uitvoer.har>`);
  process.exit(2);
}
const prefix = pluginId.toUpperCase();
const user = process.env[`${prefix}_USERNAME`];
const pass = process.env[`${prefix}_PASSWORD`];
if (!user || !pass) {
  console.error(`${prefix}_USERNAME/${prefix}_PASSWORD ontbreken`);
  process.exit(2);
}

const work = mkdtempSync(join(tmpdir(), "har-"));
const rawHar = join(work, "raw.har");
const browser = await chromium.launch({ channel: process.env.CHROME_CHANNEL || undefined, headless: true });
const context = await browser.newContext({
  locale: "nl-BE",
  recordHar: { path: rawHar, content: "embed", mode: "full" },
});
const page = await context.newPage();
try {
  await page.goto(flow.start, { waitUntil: "domcontentloaded" });
  await flow.login(page, user, pass);
  await flow.browseAndPlay(page);
} catch (e) {
  console.error(`opname onderbroken: ${e.message}`);
} finally {
  await context.close(); // schrijft de HAR weg
  await browser.close();
}

const stats = sanitizeHarFile(rawHar, outFile, [user, pass]);
rmSync(work, { recursive: true, force: true });
console.log(`HAR geschreven: ${outFile} (${stats.kept} requests, ${stats.dropped} weggelaten)`);

/**
 * Schoont een HAR op vóór hij naar de agent (en de CI-artifacts) gaat:
 *  - username/wachtwoord worden overal vervangen
 *  - cookie-, auth- en tokenwaarden worden gemaskeerd (namen blijven zichtbaar)
 *  - JWT's blijven leesbaar qua header/payload-structuur maar zonder handtekening
 *  - afbeeldingen, fonts, video-segmenten en grote bodies worden weggelaten
 */
import { readFileSync, writeFileSync } from "node:fs";

const SENSITIVE_HEADERS = new Set(["cookie", "set-cookie", "authorization", "x-vudrm-token", "oidcxsrf"]);
const TOKEN_KEYS = /(token|password|secret|session|refresh|access|identity|vudrm|drm)$/i;
const SKIP_TYPES = /^(image|font|video|audio)\//;
const SKIP_URLS = /\.(m4s|ts|mp4|aac|woff2?|png|jpe?g|webp|svg|gif)(\?|$)/i;
const MAX_BODY = 200_000;

function mask(value) {
  if (typeof value !== "string" || value.length === 0) return value;
  // JWT: header en payload behouden (nuttig voor diagnose), handtekening weg.
  const jwt = value.match(/^([\w-]+)\.([\w-]+)\.[\w-]+$/);
  if (jwt) {
    try {
      const payload = JSON.parse(Buffer.from(jwt[2], "base64url").toString());
      for (const k of Object.keys(payload)) if (TOKEN_KEYS.test(k)) payload[k] = "***";
      return `${jwt[1]}.${Buffer.from(JSON.stringify(payload)).toString("base64url")}.<redacted>`;
    } catch { /* geen echte JWT */ }
  }
  return `<redacted:${value.length}>`;
}

function scrubJson(obj) {
  if (Array.isArray(obj)) return obj.map(scrubJson);
  if (obj && typeof obj === "object") {
    const out = {};
    for (const [k, v] of Object.entries(obj)) out[k] = TOKEN_KEYS.test(k) && typeof v === "string" ? mask(v) : scrubJson(v);
    return out;
  }
  return obj;
}

function scrubText(text, secrets, mimeType = "") {
  if (typeof text !== "string") return text;
  let t = text;
  for (const s of secrets) if (s) t = t.split(s).join("<secret>");
  if (/json/.test(mimeType) || /^[\[{]/.test(t.trim())) {
    try { return JSON.stringify(scrubJson(JSON.parse(t))); } catch { /* geen JSON */ }
  }
  return t.length > MAX_BODY ? `${t.slice(0, MAX_BODY)}…<afgekapt ${t.length - MAX_BODY} tekens>` : t;
}

function scrubHeaders(headers) {
  return (headers || []).map((h) => {
    const name = h.name.toLowerCase();
    if (!SENSITIVE_HEADERS.has(name)) return h;
    if (name === "cookie") {
      return { ...h, value: h.value.split(";").map((c) => `${c.split("=")[0].trim()}=<redacted>`).join("; ") };
    }
    if (name === "set-cookie") return { ...h, value: h.value.replace(/^([^=]+)=[^;]*/, "$1=<redacted>") };
    return { ...h, value: mask(h.value) };
  });
}

function scrubUrl(url) {
  try {
    const u = new URL(url);
    for (const [k, v] of u.searchParams) if (TOKEN_KEYS.test(k)) u.searchParams.set(k, mask(v));
    return u.toString();
  } catch { return url; }
}

export function sanitizeHarFile(inFile, outFile, secrets = []) {
  const har = JSON.parse(readFileSync(inFile, "utf8"));
  let kept = 0, dropped = 0;
  har.log.entries = har.log.entries.filter((e) => {
    const type = e.response?.content?.mimeType || "";
    const drop = SKIP_TYPES.test(type) || SKIP_URLS.test(e.request.url);
    drop ? dropped++ : kept++;
    return !drop;
  }).map((e) => {
    e.request.url = scrubUrl(e.request.url);
    e.request.headers = scrubHeaders(e.request.headers);
    e.request.cookies = (e.request.cookies || []).map((c) => ({ ...c, value: "<redacted>" }));
    e.request.queryString = (e.request.queryString || []).map((q) => (TOKEN_KEYS.test(q.name) ? { ...q, value: mask(q.value) } : q));
    if (e.request.postData) {
      e.request.postData.text = scrubText(e.request.postData.text, secrets, e.request.postData.mimeType);
      delete e.request.postData.params;
    }
    e.response.headers = scrubHeaders(e.response.headers);
    e.response.cookies = (e.response.cookies || []).map((c) => ({ ...c, value: "<redacted>" }));
    const c = e.response.content || {};
    if (c.encoding === "base64") { c.text = `<binair, ${c.size} bytes>`; delete c.encoding; }
    else c.text = scrubText(c.text, secrets, c.mimeType);
    return e;
  });
  writeFileSync(outFile, JSON.stringify(har, null, 1));
  return { kept, dropped };
}

if (import.meta.url === `file://${process.argv[1]}`) {
  const [, , inFile, outFile] = process.argv;
  const stats = sanitizeHarFile(inFile, outFile, [process.env.SECRET_1, process.env.SECRET_2]);
  console.log(stats);
}

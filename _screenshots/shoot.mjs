// Screenshot harness: serves dist/renderer and mocks window.swarm (Electron preload) with sample data.
import http from 'node:http'; import fs from 'node:fs'; import path from 'node:path';
import { createRequire } from 'node:module';
const require = createRequire(import.meta.url);
const { chromium } = require('playwright-core');
const prefix = process.argv[2] || 'before';
const root = path.resolve(process.env.SHOOT_ROOT || 'dist/renderer');
const mime = { '.html':'text/html', '.js':'text/javascript', '.css':'text/css', '.png':'image/png', '.woff2':'font/woff2', '.ico':'image/x-icon' };
const server = http.createServer((req, res) => {
  let p = decodeURIComponent(req.url.split('?')[0]); if (p === '/') p = '/index.html';
  const f = path.join(root, p);
  if (!f.startsWith(root) || !fs.existsSync(f)) { res.statusCode = 404; return res.end(); }
  res.setHeader('content-type', mime[path.extname(f)] || 'application/octet-stream'); fs.createReadStream(f).pipe(res);
});
await new Promise(r => server.listen(4599, r));
const settings = JSON.parse(fs.readFileSync('_screenshots/_settings.json', 'utf8'));
const now = Date.now();
const mk = (p, id, name, status) => ({ id, name, path: 'C:/Projects/' + name, objective: 'Build a ' + name, status, favorite: id==='p1', archived: false, isDemo: false, external: false, createdAt: now-9e8, updatedAt: now-3e6, lastOpenedAt: now-3e6, lastRunId: null, memory: { objective:'', summary:'', completedTasks:[], unresolved:[], decisions:[], architecture:'', lastRunOutcome:'' }, settings: { webResearch: true, autonomy: null, stack: null } });
const projects = [mk(0,'p1','Landing Page','completed'), mk(0,'p2','Todo Desktop App','running'), mk(0,'p3','API Service','idle'), mk(0,'p4','Habit Tracker','attention')];
const model = (prov, id, name, free, health) => ({ id: prov+'::'+id, providerId: prov, modelId: id, displayName: name, capabilities: ['chat','code'], contextLength: 128000, maxOutput: 8192, freeStatus: free, health, streaming: true, enabled: true, paramsB: 70, latencyMs: 420, tokensPerSec: 55, calls: 120, successes: 115, failures: 5, consecutiveFailures: 0, lastError: null, lastErrorAt: null, lastSuccessAt: now-1e5, lastCheckedAt: now-1e5, rateLimitedUntil: null, discoveredAt: now-1e8, notes: null });
const models = [model('groq','llama-3.3-70b','Llama 3.3 70B','free','healthy'), model('openrouter','qwen-coder','Qwen3 Coder','free','healthy'), model('ollama','llama3','Llama 3 8B (local)','local','healthy'), model('gemini','flash','Gemini Flash','free','rate_limited')];
const prov = (id, name, kind) => ({ id, name, kind, requiresKey: kind==='cloud', needsAccountId:false, configured:true, enabled:true, keyHint:'3f9a', keySource:'settings', baseUrl:'https://api.example.com', health:'healthy', modelCount:5, freeModelCount:3, lastDiscoveryAt: now-1e6, lastError:null, latencyMs:300, signupUrl:'https://example.com', freeNotes:'Generous free tier' });
const providers = [prov('groq','Groq','cloud'), prov('openrouter','OpenRouter','cloud'), prov('ollama','Ollama','local'), prov('gemini','Gemini','cloud')];
const conv = { id:'c1', title:'Designing a landing page', updatedAt: now-2e5, turns: [
  { id:'t1', role:'user', text:'Can you outline a clean landing page layout with a hero, features and pricing?', status:'complete' },
  { id:'t2', role:'assistant', text:'Sure! Here is a layout:\n\n## Structure\n\n- **Hero** with headline and call to action\n- **Features** in a three column grid\n- **Pricing** with two tiers\n\n```tsx\nexport function Hero() {\n  return <h1 className="title">Ship faster</h1>;\n}\n```\n\nLet me know if you want the `CSS` too.', status:'complete', model:'groq::llama-3.3-70b', tokens: 312 },
]};
const convs = [conv, { id:'c2', title:'Explain React hooks', updatedAt: now-9e7, turns: [] }, { id:'c3', title:'SQL query help', updatedAt: now-3e8, turns: [] }];
const data = { settings, projects, models, providers, convs, conv, now };
const browser = await chromium.launch({ headless: true });
async function shoot(theme, name, nav) {
  const ctx = await browser.newContext({ viewport: { width: 1440, height: 900 }, colorScheme: theme, deviceScaleFactor: 1 });
  const page = await ctx.newPage();
  page.on('pageerror', e => console.log('pageerror', e.message));
  await page.addInitScript((d) => {
    const s = JSON.parse(JSON.stringify(d.settings)); s.appearance.theme = d.theme;
    const h = {
      'app:bootstrap': () => ({ firstRun:false, version:'1.0.0', platform:'win32', paths:{data:'C:/d',workspace:'C:/w'}, ollama:{installed:true,running:true,url:'http://localhost:11434',version:'0.5'}, lastProjectId:null }),
      'settings:get': () => s, 'projects:list': () => d.projects, 'models:list': () => ({ models:d.models, providers:d.providers }),
      'notifications:list': () => [], 'approvals:list': () => [], 'runs:active': () => [], 'chat:list': () => d.convs.map(c=>({...c,turns:[]})),
      'chat:get': () => d.conv, 'chat:search': () => [], 'runs:list': () => [], 'app:setTitleBar': () => true,
      'ollama:status': () => ({running:true,version:'0.5'}), 'models:usage': () => ({ cloudUsd:0, localUsd:0, totalUsd:0, calls:80, promptTokens:9000, completionTokens:3000, byProvider: [], byModel: [] }), 'models:rank': () => [],
      'app:dataStats': () => ({events:1,runs:1,cache:1,usage:1,sources:1,dbBytes:1000,dbPath:'C:/d/db'}),
    };
    window.swarm = { platform:'win32', on: () => () => {}, invoke: async (ch) => { const f = h[ch]; return f ? f() : null; } };
    localStorage.setItem('swarm:chat', 'c1');
  }, { ...data, theme });
  await page.goto('http://localhost:4599/'); await page.waitForTimeout(1200);
  if (nav) { await nav(page); }
  await page.waitForTimeout(700);
  const out = path.resolve('_screenshots', `${prefix}_${name}_${theme}.png`);
  await page.screenshot({ path: out }); console.log('saved', out); await ctx.close();
}
const clickText = (t) => async (page) => { await page.getByText(t, { exact: true }).first().click({ timeout: 3000 }).catch(e => console.log('nav fail', t)); };
for (const theme of ['light', 'dark']) {
  await shoot(theme, 'chat', null);
  await shoot(theme, 'models', clickText('Models'));
  await shoot(theme, 'settings', clickText('Settings'));
  await shoot(theme, 'build', clickText('Build projects'));
  await shoot(theme, 'projects', async (p) => { await clickText('Build projects')(p); await clickText('Projects')(p); });
}
await browser.close(); server.close();




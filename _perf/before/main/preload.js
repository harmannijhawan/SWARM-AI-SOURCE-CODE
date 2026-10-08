"use strict";

// electron/preload.ts
var import_electron = require("electron");
async function invoke(channel, ...args) {
  const r = await import_electron.ipcRenderer.invoke(channel, ...args);
  if (!r.ok) throw new Error(r.error);
  return r.data;
}
var ALLOWED_EVENTS = /* @__PURE__ */ new Set(["chat:updated", "events", "run:updated", "models:changed", "settings:changed", "notification", "approvals:changed", "menu", "run:chat:updated", "remote:changed"]);
import_electron.contextBridge.exposeInMainWorld("swarm", {
  invoke,
  on(channel, fn) {
    if (!ALLOWED_EVENTS.has(channel)) throw new Error(`Channel not allowed: ${channel}`);
    const listener = (_e, payload) => fn(payload);
    import_electron.ipcRenderer.on(channel, listener);
    return () => import_electron.ipcRenderer.removeListener(channel, listener);
  },
  platform: process.platform
});

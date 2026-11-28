const assert = require("node:assert/strict");
const fs = require("node:fs");
const vm = require("node:vm");
const path = require("node:path");
const source = fs.readFileSync(path.join(__dirname, "../app/src/main/assets/extensions/droidtop-hidden/background.js"), "utf8");
const timers = new Map();
let next = 0, message, disconnect, aborted = 0;
const port = { onMessage: { addListener(fn) { message = fn; } }, onDisconnect: { addListener(fn) { disconnect = fn; } }, postMessage() {} };
const scope = {
  setInterval(fn, ms) { const id = ++next; timers.set(id, { fn, ms }); return id; },
  clearInterval(id) { timers.delete(id); },
  browser: { runtime: { connectNative() { return port; } }, droidtopHiddenBootstrap: {
    async register() { return true; }, async unregister() {}, async abortAll() { aborted++; }, async pending() { return "[]"; },
  } },
};
vm.createContext(scope); vm.runInContext(source, scope);
const polls = () => [...timers.values()].filter(timer => timer.ms === 25).length;
(async () => {
  assert.equal(polls(), 0, "an idle warm runtime must not poll authorization at 40Hz");
  await message({ id: "1", op: "register", contextId: "first", timeoutMs: 30000, proxy: {} });
  assert.equal(polls(), 1);
  await message({ id: "2", op: "register", contextId: "second", timeoutMs: 30000, proxy: {} });
  assert.equal(polls(), 1, "concurrent contexts share one polling loop");
  await message({ id: "3", op: "unregister", contextId: "first" });
  assert.equal(polls(), 1);
  await message({ id: "4", op: "unregister", contextId: "second" });
  assert.equal(polls(), 0);
  await message({ id: "5", op: "register", contextId: "third", timeoutMs: 30000, proxy: {} });
  disconnect();
  assert.equal(polls(), 0);
  assert.equal(aborted, 1);
  console.log("Hidden background: polling runs only for active contexts and stops on disconnect");
})().catch(error => { console.error(error); process.exitCode = 1; });

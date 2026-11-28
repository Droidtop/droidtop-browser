const assert = require("node:assert/strict");
const fs = require("node:fs");
const vm = require("node:vm");
const path = require("node:path");
const source = fs.readFileSync(path.join(__dirname, "../app/src/main/assets/extensions/droidtop-hidden/channel-guard.js"), "utf8");
const first = "droidtop-hidden-11111111-1111-4111-8111-111111111111";
const second = "droidtop-hidden-22222222-2222-4222-8222-222222222222";
const safe = id => "gvctx" + Buffer.from(id).toString("hex");
const proxy = { host: "127.0.0.1", port: 45678, authorization: "Bearer " + "a".repeat(43), isolationKey: "11111111-1111-4111-8111-111111111111" };
let clock = 1000;
const timers = new Map();
let nextTimer = 0;
const scope = {
  ChromeUtils: { generateQI() { return function() { return this; }; } },
  Cc: { "@mozilla.org/network/protocol-proxy-service;1": { getService() { return {
    newProxyInfo(...args) { return { args }; }, registerChannelFilter() {}, unregisterChannelFilter() {},
  }; } } },
  Services: { obs: { addObserver() {} } },
  Ci: { nsIHttpChannel: {}, nsIContentPolicy: { TYPE_DOCUMENT: 1, TYPE_SUBDOCUMENT: 2 } },
  Cr: { NS_BINDING_ABORTED: "cancelled" },
  Date: { now: () => clock },
  setTimeout(fn) { const id = ++nextTimer; timers.set(id, fn); return id; },
  clearTimeout(id) { timers.delete(id); },
};
vm.createContext(scope); vm.runInContext(source, scope);
const guard = new scope.HiddenChannels(); guard.ready = true;
assert.equal(guard.register(first, 30000, proxy), true);
function channel(context = first, type = 9) {
  const events = [];
  return {
    events, sent: 0, cancelled: false,
    QueryInterface() { return this; },
    loadInfo: { originAttributes: { privateBrowsingId: 1, geckoViewSessionContextId: safe(context) }, externalContentPolicyType: type },
    URI: { spec: "https://example.com/resource", schemeIs(value) { return value === "https"; } },
    setRequestHeader(name, value) { this.proxyAuthorization = { name, value }; },
    suspend() { events.push("suspend"); },
    cancel() { events.push("cancel"); this.cancelled = true; },
    resume() { events.push("resume"); if (!this.cancelled) this.sent++; },
  };
}
const allowed = channel(); guard.observe(allowed, "http-on-modify-request");
let selected;
guard.proxyFilter.applyFilter(allowed, null, { onProxyFilterResult(value) { selected = value; } });
assert.equal(selected.args[4], proxy.isolationKey);
assert.equal(selected.args[7], null); // No DIRECT failover chain.
assert.equal(allowed.proxyAuthorization.value, proxy.authorization);
assert.equal(allowed.sent, 0); assert.deepEqual(allowed.events, ["suspend"]);
const request = JSON.parse(guard.pending())[0]; assert.equal(request.kind, "resource");
guard.authorize(request.id, true);
assert.equal(allowed.sent, 1); assert.deepEqual(allowed.events, ["suspend", "resume"]);

// No tabId is needed: a worker channel is identified by its exact private context.
const worker = channel(); guard.observe(worker, "http-on-modify-request");
const deny = JSON.parse(guard.pending())[0]; guard.authorize(deny.id, false);
assert.equal(worker.sent, 0); assert.deepEqual(worker.events, ["suspend", "cancel", "resume"]);
assert.equal(JSON.parse(guard.info(first)).denied, true);
const afterDeny = channel(); guard.observe(afterDeny, "http-on-modify-request");
assert.deepEqual(afterDeny.events, ["cancel"]);

// No unrelated/default-origin channel may escape the dedicated process. Ordinary Browser
// uses another runtime without this extension.
const visible = channel("droidtop-11111111-1111-4111-8111-111111111111");
guard.observe(visible, "http-on-modify-request"); assert.deepEqual(visible.events, ["cancel"]);
const background = channel(); background.loadInfo.originAttributes = {};
guard.observe(background, "http-on-modify-request"); assert.deepEqual(background.events, ["cancel"]);
const unknown = channel(second); guard.observe(unknown, "http-on-modify-request"); assert.deepEqual(unknown.events, ["cancel"]);
assert.equal(guard.register(second, 30000, proxy), true);
const expiring = channel(second); guard.observe(expiring, "http-on-modify-request");
const timedOut = JSON.parse(guard.pending())[0];
for (const fn of [...timers.values()]) fn();
assert.equal(expiring.sent, 0); assert.deepEqual(expiring.events, ["suspend", "cancel", "resume"]);
guard.authorize(timedOut.id, true); assert.equal(expiring.sent, 0);
guard.unregister(first); guard.unregister(second);

assert.equal(guard.register(first, 30000, proxy), true);
const losingPort = channel(); guard.observe(losingPort, "http-on-modify-request");
guard.abortAll();
assert.equal(losingPort.sent, 0); assert.deepEqual(losingPort.events, ["suspend", "cancel", "resume"]);
assert.equal(guard.requests.size, 0); assert.equal(guard.scopes.size, 0);
const unscoped = channel(); unscoped.loadInfo.originAttributes = {};
guard.proxyFilter.applyFilter(unscoped, null, { onProxyFilterResult(value) { assert.equal(value, guard.blockedProxy); } });
assert.equal(unscoped.cancelled, true);
console.log("Hidden channel guard: before-send authorization, worker scope, deny/deadline/port cleanup pass");

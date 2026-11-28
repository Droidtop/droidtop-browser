const assert = require("node:assert/strict");
const fs = require("node:fs");
const vm = require("node:vm");
const path = require("node:path");
const base = path.join(__dirname, "../app/src/main/assets/extensions/droidtop-hidden");
const required = JSON.parse(fs.readFileSync(path.join(base, "required-prefs.json"), "utf8"));
const source = fs.readFileSync(path.join(base, "bootstrap-api.js"), "utf8");
const guardSource = fs.readFileSync(path.join(base, "channel-guard.js"), "utf8");
const cookieSource = fs.readFileSync(path.join(base, "../droidtop/session-api.js"), "utf8");
function instance(profile, actual, defaults = required, engineVersion = "160.0.1") {
  const locks = [];
  const scope = { ExtensionAPI: class {}, Ci: { nsIFile: {} },
    Cc: { "@mozilla.org/network/protocol-proxy-service;1": { getService() { return { newProxyInfo() { return {}; }, registerChannelFilter() {} }; } } },
    ChromeUtils: { generateQI() { return function() { return this; }; }, importESModule() { return { setTimeout, clearTimeout }; } }, Services: {
    scriptloader: { loadSubScript(url) { vm.runInContext(url.endsWith("session-api.js") ? cookieSource : guardSource, scope); } },
    obs: { addObserver() {} },
    dirsvc: { get() { return { path: profile, normalize() {} }; } },
    appinfo: { platformVersion: engineVersion },
    prefs: {
      getDefaultBranch() { return {
        getPrefType(key) { return key in defaults ? 128 : 0; },
        setBoolPref(key, value) { assert.equal(actual[key], value); },
        setIntPref(key, value) { assert.equal(actual[key], value); },
        setStringPref(key, value) { assert.equal(actual[key], value); },
      }; },
      getBoolPref(key) { assert.equal(typeof actual[key], "boolean"); return actual[key]; },
      getIntPref(key) { assert.equal(typeof actual[key], "number"); return actual[key]; },
      getStringPref(key) { assert.equal(typeof actual[key], "string"); return actual[key]; },
      lockPref(key) { locks.push(key); },
    },
  } };
  vm.createContext(scope); vm.runInContext(source, scope);
  return { api: new scope.droidtopHiddenBootstrap().getAPI().droidtopHiddenBootstrap, locks };
}
(async () => {
  const owned = "/data/user/0/dev.droidtop.browser/no_backup/droidtop-web-runtime/profile";
  const good = instance(owned, required);
  assert.equal(JSON.parse(await good.api.attest(owned, JSON.stringify(required))).ok, true);
  assert.equal(good.locks.length, Object.keys(required).length);
  const wrongProfile = instance("/data/user/0/dev.droidtop.browser/files/mozilla/default", required);
  assert.equal(JSON.parse(await wrongProfile.api.attest(owned, JSON.stringify(required))).ok, false);
  assert.equal(wrongProfile.locks.length, 0);
  const wrongEngine = instance(owned, required, required, "161.0");
  assert.equal(JSON.parse(await wrongEngine.api.attest(owned, JSON.stringify(required))).ok, false);
  const missingDefault = instance(owned, required, {});
  assert.equal(JSON.parse(await missingDefault.api.attest(owned, JSON.stringify(required))).ok, false);
  assert.equal(missingDefault.locks.length, 0);
  for (const key of Object.keys(required)) {
    const changed = { ...required, [key]: typeof required[key] === "boolean" ? !required[key] : required[key] + 1 };
    const bad = instance(owned, changed);
    assert.equal(JSON.parse(await bad.api.attest(owned, JSON.stringify(required))).ok, false, key);
    assert.equal(bad.locks.length, 0);
  }
  assert.equal(required["network.preconnect"], false);
  assert.equal(required["network.http.speculative-parallel-limit"], 0);
  assert.equal(required["network.dns.disablePrefetch"], true);
  assert.equal(required["media.peerconnection.enabled"], false);
  console.log("Restricted bootstrap: exact profile, all pref mismatches and lock-only-after-match pass");
})().catch(error => { console.error(error); process.exitCode = 1; });

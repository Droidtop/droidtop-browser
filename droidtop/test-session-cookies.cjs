// Source-level unit tests for the privileged boundary; these do not run Gecko or prove its runtime integration.
const assert = require("node:assert/strict");
const fs = require("node:fs");
const vm = require("node:vm");
const path = require("node:path");
const records = [];
const removed = [];
const first = "droidtop-11111111-1111-4111-8111-111111111111";
const second = "droidtop-22222222-2222-4222-8222-222222222222";
const source = fs.readFileSync(path.join(__dirname, "../app/src/main/assets/extensions/droidtop/session-api.js"), "utf8");
const scope = {
  ExtensionAPI: class {},
  Ci: { nsICookie: { SAMESITE_NONE: 0, SCHEME_HTTPS: 2 }, nsICookieValidation: { eOK: 0 } },
  Services: { cookies: {
    getCookiesWithOriginAttributes(pattern) {
      const attrs = JSON.parse(pattern);
      assert.equal(attrs.privateBrowsingId, 1);
      assert.match(attrs.geckoViewSessionContextId, /^gvctx[0-9a-f]+$/);
      return records.filter(c => c.originAttributes.geckoViewSessionContextId === attrs.geckoViewSessionContextId);
    },
    add(host, cookiePath, name, value, secure, httpOnly, session, expiry, attrs) {
      records.push({ host, path: cookiePath, name, value, isDomain: host.startsWith("."), originAttributes: attrs });
      assert.equal(secure, true);
      assert.equal(session, true);
      return { result: 0 };
    },
    remove(host, name, cookiePath, attrs) {
      removed.push(attrs.geckoViewSessionContextId);
      const i = records.findIndex(c => c.host === host && c.name === name && c.path === cookiePath &&
        c.originAttributes.geckoViewSessionContextId === attrs.geckoViewSessionContextId);
      if (i >= 0) records.splice(i, 1);
    },
  } },
};
vm.createContext(scope);
vm.runInContext(source, scope);
const api = new scope.droidtopSessionCookies().getAPI().droidtopSessionCookies;
const call = (id, op, data) => api.call(id, op, JSON.stringify(data));
(async () => {
  await call(first, "set", { cookies: { "example.com": "session=first" } });
  await call(second, "set", { cookies: { "example.com": "session=second" } });
  const safe = "gvctx" + Buffer.from(first, "utf8").toString("hex");
  assert.equal(records[0].originAttributes.geckoViewSessionContextId, safe);
  assert.deepEqual(JSON.parse(await call(first, "get", { domains: ["example.com", "unrelated.example"] })),
    { "example.com": "session=first", "unrelated.example": "" });
  // Domain cookies apply to a child, host-only cookies do not leak to a sibling or parent.
  records.push({ host: "login.example.com", path: "/", name: "hostOnly", value: "secret", isDomain: false,
    originAttributes: records[0].originAttributes });
  const parent = JSON.parse(await call(first, "get", { domains: ["example.com"] }));
  assert.equal(parent["example.com"], "session=first");
  await call(first, "clear", { domains: ["example.com"] });
  assert.ok(removed.every(id => id === safe));
  assert.equal(JSON.parse(await call(second, "get", { domains: ["example.com"] }))["example.com"], "session=second");
  await assert.rejects(call("", "get", { domains: ["example.com"] }));
  await assert.rejects(call("firefox-private", "clear", { domains: ["example.com"] }));
  await assert.rejects(call(first, "get", { domains: ["https://example.com"] }));
  await assert.rejects(call(first, "set", { cookies: { "example.com": "a=x\r\nInjected:y" } }));
  await assert.rejects(call(first, "arbitrary-script", { domains: [] }));
  console.log("Scoped cookie boundary: context isolation, cookie scope and rejected unsafe input pass");
})().catch(error => { console.error(error); process.exitCode = 1; });

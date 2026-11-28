"use strict";

const hiddenTimers = ChromeUtils.importESModule("resource://gre/modules/Timer.sys.mjs");
this.setTimeout = hiddenTimers.setTimeout;
this.clearTimeout = hiddenTimers.clearTimeout;
Services.scriptloader.loadSubScript("resource://android/assets/extensions/droidtop-hidden/channel-guard.js", this);
// Reuse the same exact-context cookie implementation; no second jar policy or regex copy.
Services.scriptloader.loadSubScript("resource://android/assets/extensions/droidtop/session-api.js", this);

/* Runs only in the separately owned profile/process. Never change preferences in the ordinary Browser runtime. */
this.droidtopHiddenBootstrap = class extends ExtensionAPI {
  constructor(...args) { super(...args); this.guard = new HiddenChannels(); }
  onShutdown() { this.guard.close(); }
  getAPI(context) {
    const guard = this.guard;
    const cookies = new droidtopSessionCookies(this.extension).getAPI().droidtopSessionCookies;
    return { droidtopHiddenBootstrap: {
      async attest(expectedProfile, encodedPrefs) {
        const profile = Services.dirsvc.get("ProfD", Ci.nsIFile);
        profile.normalize();
        const required = JSON.parse(encodedPrefs);
        const actual = Object.create(null);
        const engineMajor = Number.parseInt(Services.appinfo.platformVersion, 10);
        let ok = profile.path === expectedProfile && engineMajor === 160;
        const defaults = Services.prefs.getDefaultBranch("");
        for (const [key, wanted] of Object.entries(required)) {
          try {
            // A user value for an unknown/removed preference would otherwise falsely attest a restriction that
            // this engine does not implement. The pinned engine must declare a default for every required key.
            if (defaults.getPrefType(key) === 0) { ok = false; continue; }
            actual[key] = typeof wanted === "boolean" ? Services.prefs.getBoolPref(key) : typeof wanted === "string" ? Services.prefs.getStringPref(key) : Services.prefs.getIntPref(key);
            if (actual[key] !== wanted) ok = false;
          } catch (error) { ok = false; }
        }
        if (ok) {
          for (const [key, wanted] of Object.entries(required)) {
            // Locked prefs resolve through the default branch. Preserve the already verified
            // restriction there, then read it back; locking must never restore an unsafe default.
            if (typeof wanted === "boolean") defaults.setBoolPref(key, wanted);
            else if (typeof wanted === "string") defaults.setStringPref(key, wanted);
            else defaults.setIntPref(key, wanted);
            Services.prefs.lockPref(key);
            actual[key] = typeof wanted === "boolean" ? Services.prefs.getBoolPref(key) : typeof wanted === "string" ? Services.prefs.getStringPref(key) : Services.prefs.getIntPref(key);
            if (actual[key] !== wanted) ok = false;
          }
          guard.ready = ok;
          if (!ok) guard.abortAll();
        } else guard.abortAll();
        return JSON.stringify({ ok, profile: profile.path, engineMajor, prefs: actual });
      },
      async register(contextId, timeoutMs, proxyJson) { return guard.register(contextId, timeoutMs, JSON.parse(proxyJson)); },
      async unregister(contextId) { guard.unregister(contextId); },
      async pending() { return guard.pending(); },
      async authorize(id, allowed) { guard.authorize(id, allowed); },
      async info(contextId) { return guard.info(contextId); },
      async abortAll() { guard.abortAll(); },
      async cookies(contextId, op, host, header) {
        const scope = guard.scopes.get(guard.safe(contextId));
        if (!guard.ready || !scope || scope.expires <= Date.now()) throw new Error("closed cookie context");
        // The native controller supplies the immutable initial host only. Bind its first use
        // to the context and reject later widening; all import/export remains in this runtime.
        if (!scope.cookieHost) scope.cookieHost = domainName(host);
        if (scope.cookieHost !== domainName(host)) throw new Error("cookie host changed");
        if (!['set', 'get', 'clear'].includes(op) || typeof header !== 'string' || header.length > 16384 || /[\u0000-\u001f\u007f]/.test(header)) throw new Error("invalid cookie operation");
        return cookies.call(contextId, op, JSON.stringify(op === 'set' ? { cookies: { [host]: header } } : { domains: [host] }));
      },
      async tab(contextId) {
        const safe = guard.safe(contextId);
        if (!guard.ready || !guard.scopes.has(safe)) return -1;
        for (const tab of context.extension.tabManager.query({}, context)) {
          try {
            const attrs = tab.browser.browsingContext.originAttributes;
            if (attrs.privateBrowsingId === 1 && attrs.geckoViewSessionContextId === safe) return tab.id;
          } catch (error) { /* A closing or unavailable tab is never a substitute for the requested context. */ }
        }
        return -1;
      },
    } };
  }
};

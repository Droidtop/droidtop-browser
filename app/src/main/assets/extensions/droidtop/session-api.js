/* Built-in privileged experiment. Gecko 160's public cookies API cannot address geckoViewSessionContextId.
 * Use the exact origin attributes of our own generated private session, never the user's global private store.
 * Services.cookies signatures and safe-context encoding match Firefox 160 release source. */
"use strict";

function attributes(contextId) {
  if (!/^droidtop-[0-9a-f]{8}-[0-9a-f]{4}-[0-9a-f]{4}-[0-9a-f]{4}-[0-9a-f]{12}$/.test(contextId)) {
    throw new Error("invalid session context");
  }
  // StorageController.createSafeSessionContextId uses BigInteger(UTF8).toString(16); our ASCII prefix has no
  // sign/leading-zero ambiguity. Never accept an already-encoded origin attribute from a caller.
  const safe = "gvctx" + Array.from(contextId, c => c.charCodeAt(0).toString(16).padStart(2, "0")).join("");
  return { privateBrowsingId: 1, geckoViewSessionContextId: safe };
}

function domainName(domain) {
  if (typeof domain !== "string" || domain.length > 253 || !/^[a-z0-9.-]+$/i.test(domain) ||
      domain.startsWith(".") || domain.endsWith(".") || domain.includes("..")) throw new Error("invalid cookie domain");
  return domain.toLowerCase();
}

function matches(cookie, domain) {
  const host = cookie.host.replace(/^\./, "").toLowerCase();
  return cookie.isDomain ? domain === host || domain.endsWith("." + host) : domain === host;
}

function cookiesFor(attrs, domain) {
  // The exact context ID and private flag are required in EVERY query. getCookiesFromHost is base-domain-wide;
  // independently filter RFC host/domain scope before returning a header for this root URL.
  return Services.cookies.getCookiesWithOriginAttributes(JSON.stringify(attrs), domain, true)
    .filter(c => matches(c, domain) && c.path === "/" && !c.originAttributes.partitionKey &&
      !c.originAttributes.firstPartyDomain && (c.isSession || c.expiry > Math.floor(Date.now() / 1000)));
}

function clear(attrs, domain) {
  for (const c of Services.cookies.getCookiesWithOriginAttributes(JSON.stringify(attrs), domain)) {
    const host = c.host.replace(/^\./, "").toLowerCase();
    if (host === domain || host.endsWith("." + domain) || matches(c, domain)) {
      Services.cookies.remove(c.host, c.name, c.path, c.originAttributes);
    }
  }
}

this.droidtopSessionCookies = class extends ExtensionAPI {
  getAPI() {
    return {
      droidtopSessionCookies: {
        async call(contextId, op, payload) {
          const attrs = attributes(contextId);
          if (payload.length > 262144) throw new Error("cookie message too large");
          const data = JSON.parse(payload);
          if (op === "set") {
            const entries = Object.entries(data.cookies || {});
            if (entries.length > 64) throw new Error("too many cookie domains");
            for (const [rawDomain, header] of entries) {
              const domain = domainName(rawDomain);
              if (typeof header !== "string" || header.length > 32768 || /[\r\n\0]/.test(header)) throw new Error("invalid cookie header");
              clear(attrs, domain);
              for (const part of header.split(";")) {
                const pair = part.trim();
                const eq = pair.indexOf("=");
                if (eq <= 0) continue;
                // A legacy Cookie header has lost Domain/Path/HttpOnly attributes. Do not widen it to subdomains
                // or make an authentication cookie readable to page script. Fresh site cookies keep their real
                // attributes; imported unknown cookies conservatively use exact-host + HttpOnly + root path.
                const result = Services.cookies.add(domain, "/", pair.slice(0, eq), pair.slice(eq + 1),
                  true, true, true, Math.floor(Date.now() / 1000) + 86400, attrs,
                  Ci.nsICookie.SAMESITE_LAX, Ci.nsICookie.SCHEME_HTTPS);
                if (result.result !== Ci.nsICookieValidation.eOK) throw new Error("cookie rejected");
              }
            }
            return "{}";
          }
          if (!Array.isArray(data.domains) || data.domains.length > 64) throw new Error("invalid cookie domains");
          const domains = data.domains.map(domainName);
          if (op === "get") {
            const result = Object.create(null);
            for (const domain of domains) result[domain] = cookiesFor(attrs, domain).map(c => `${c.name}=${c.value}`).join("; ");
            return JSON.stringify(result);
          }
          if (op === "clear") {
            for (const domain of domains) clear(attrs, domain);
            return "{}";
          }
          throw new Error("unknown session operation");
        },
      },
    };
  }
};

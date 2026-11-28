"use strict";

/* Scoped HTTP pre-connect authorization. nsHttpChannel::ContinuePrepareToConnect waits for resume after
 * http-on-modify-request observers; this runs only in the attested dedicated process/profile. */
this.HiddenChannels = class {
  constructor() {
    this.scopes = new Map();
    this.requests = new Map();
    this.nextId = 0;
    this.ready = false;
    this.proxies = Cc["@mozilla.org/network/protocol-proxy-service;1"].getService(Ci.nsIProtocolProxyService);
    // A non-null dead-end proxy is mandatory: null means DIRECT to Gecko.
    this.blockedProxy = this.proxies.newProxyInfo("http", "127.0.0.1", Services.prefs.getIntPref("network.proxy.ssl_port"), "", "droidtop-denied", 0, 0, null);
    this.proxyFilter = {
      QueryInterface: ChromeUtils.generateQI(["nsIProtocolProxyChannelFilter"]),
      applyFilter: (channel, previous, callback) => {
        let selected = this.blockedProxy;
        try {
          const attrs = channel.loadInfo.originAttributes;
          const scope = this.scopes.get(attrs.geckoViewSessionContextId);
          if (!this.ready || attrs.privateBrowsingId !== 1 || !scope || scope.denied || scope.expires <= Date.now() || !channel.URI.schemeIs("https")) throw new Error("unauthorized channel");
          // CONNECT authentication is never part of a caller request or exposed to page JS.
          // Gecko strips proxy headers from the TLS origin request when UsingConnect().
          channel.QueryInterface(Ci.nsIHttpChannel).setRequestHeader("Proxy-Authorization", scope.proxy.authorization, false);
          selected = scope.proxyInfo;
        } catch (error) { channel.cancel(Cr.NS_BINDING_ABORTED); }
        callback.onProxyFilterResult(selected);
      },
    };
    this.proxies.registerChannelFilter(this.proxyFilter, 0);
    this.observer = { observe: (subject, topic) => this.observe(subject, topic) };
    for (const topic of ["http-on-modify-request", "http-on-examine-response", "http-on-examine-cached-response"]) {
      Services.obs.addObserver(this.observer, topic);
    }
  }

  safe(contextId) {
    if (!/^droidtop-hidden-[0-9a-f]{8}-[0-9a-f]{4}-[0-9a-f]{4}-[0-9a-f]{4}-[0-9a-f]{12}$/.test(contextId)) throw new Error("invalid hidden context");
    return "gvctx" + Array.from(contextId, c => c.charCodeAt(0).toString(16).padStart(2, "0")).join("");
  }

  register(contextId, timeoutMs, proxy) {
    if (!this.ready || this.scopes.size >= 4 || !Number.isInteger(timeoutMs) || timeoutMs < 1 || timeoutMs > 30000) return false;
    const safe = this.safe(contextId);
    if (this.scopes.has(safe)) return false;
    if (!proxy || Object.keys(proxy).sort().join(",") !== "authorization,host,isolationKey,port" || proxy.host !== "127.0.0.1" ||
        !Number.isInteger(proxy.port) || proxy.port < 1024 || proxy.port > 65535 ||
        !/^Bearer [A-Za-z0-9_-]{43}$/.test(proxy.authorization) || !/^[0-9a-f]{8}-[0-9a-f]{4}-[0-9a-f]{4}-[0-9a-f]{4}-[0-9a-f]{12}$/.test(proxy.isolationKey)) return false;
    const proxyInfo = this.proxies.newProxyInfo("http", proxy.host, proxy.port, "", proxy.isolationKey, 0, 0, null);
    this.scopes.set(safe, { contextId, proxy, proxyInfo, expires: Date.now() + timeoutMs, pending: 0, denied: false });
    return true;
  }

  unregister(contextId) {
    const safe = this.safe(contextId);
    this.scopes.delete(safe);
    for (const [id, item] of this.requests) if (item.safe === safe) this.authorize(id, false);
  }

  observe(subject, topic) {
    let channel;
    try { channel = subject.QueryInterface(Ci.nsIHttpChannel); } catch (error) { return; }
    const attrs = channel.loadInfo?.originAttributes;
    const safe = attrs?.geckoViewSessionContextId || "";
    const prefix = "gvctx" + Array.from("droidtop-hidden-", c => c.charCodeAt(0).toString(16)).join("");
    // This extension exists ONLY in the owned runtime: background/default channels have no
    // authority. The ordinary Browser is a different process and never installs this guard.
    if (attrs?.privateBrowsingId !== 1 || !safe.startsWith(prefix)) {
      channel.cancel(Cr.NS_BINDING_ABORTED);
      return;
    }
    const scope = this.scopes.get(safe);
    if (!this.ready || !scope || scope.expires <= Date.now()) {
      channel.cancel(Cr.NS_BINDING_ABORTED);
      return;
    }
    if (topic !== "http-on-modify-request") {
      if (channel.loadInfo.externalContentPolicyType === Ci.nsIContentPolicy.TYPE_DOCUMENT) {
        try {
          const status = channel.responseStatus;
          if (status >= 100 && status <= 599) {
            scope.status = status;
            scope.url = channel.URI.spec;
            scope.contentType = channel.getResponseHeader("Content-Type").slice(0, 256);
            scope.userAgent = channel.getRequestHeader("User-Agent").slice(0, 4096);
            try { scope.cfMitigated = channel.getResponseHeader("cf-mitigated").slice(0, 64); } catch (error) { scope.cfMitigated = ""; }
          }
        } catch (error) { scope.status = null; }
      }
      return;
    }
    if (scope.denied || this.requests.size >= 64 || scope.pending >= 16) {
      scope.denied = true;
      channel.cancel(Cr.NS_BINDING_ABORTED);
      return;
    }
    let url, type;
    try {
      url = channel.URI.spec;
      type = channel.loadInfo.externalContentPolicyType;
      if (url.length > 4096 || /[\u0000-\u001f\u007f]/.test(url)) throw new Error("invalid URL");
    } catch (error) { scope.denied = true; channel.cancel(Cr.NS_BINDING_ABORTED); return; }
    try { channel.suspend(); } catch (error) { scope.denied = true; channel.cancel(Cr.NS_BINDING_ABORTED); return; }
    const kind = type === Ci.nsIContentPolicy.TYPE_DOCUMENT ? "document" : type === Ci.nsIContentPolicy.TYPE_SUBDOCUMENT ? "frame" : "resource";
    const id = String(++this.nextId);
    const item = { safe, scope, channel, sent: false, contextId: scope.contextId, url, kind };
    scope.pending++;
    this.requests.set(id, item);
    try {
      item.timer = setTimeout(() => this.authorize(id, false), Math.min(3000, Math.max(1, scope.expires - Date.now())));
    } catch (error) { this.authorize(id, false); }
  }

  pending() {
    const result = [];
    for (const [id, item] of this.requests) if (!item.sent) {
      item.sent = true;
      result.push({ id, contextId: item.contextId, url: item.url, kind: item.kind });
    }
    return JSON.stringify(result);
  }

  authorize(id, allowed) {
    const item = this.requests.get(id);
    if (!item) return;
    this.requests.delete(id);
    clearTimeout(item.timer);
    item.scope.pending--;
    try {
      if (!allowed || !this.ready || item.scope.denied || this.scopes.get(item.safe) !== item.scope || item.scope.expires <= Date.now()) {
        item.scope.denied = true;
        item.channel.cancel(Cr.NS_BINDING_ABORTED);
      }
    } finally {
      // Every successful suspend has exactly one resume, including deny, timeout, port loss and unregister.
      try { item.channel.resume(); } catch (error) { /* Already cancelled/destroyed; never retry the channel. */ }
    }
  }

  info(contextId) {
    const scope = this.scopes.get(this.safe(contextId));
    if (!scope || !this.ready || scope.expires <= Date.now()) return JSON.stringify({ denied: true });
    return JSON.stringify({ denied: scope.denied, status: scope.status || null, url: scope.url || "",
      contentType: scope.contentType || "", userAgent: scope.userAgent || "", cfMitigated: scope.cfMitigated || "" });
  }

  abortAll() {
    this.ready = false;
    for (const [id] of this.requests) this.authorize(id, false);
    this.scopes.clear();
  }

  close() {
    this.abortAll();
    this.proxies.unregisterChannelFilter(this.proxyFilter);
    for (const topic of ["http-on-modify-request", "http-on-examine-response", "http-on-examine-cached-response"]) {
      Services.obs.removeObserver(this.observer, topic);
    }
  }
};

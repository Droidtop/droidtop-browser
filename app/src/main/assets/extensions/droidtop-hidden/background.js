"use strict";
let port;
function connect() {
  if (port) return;
  try {
    const opened = browser.runtime.connectNative("droidtop-hidden");
    port = opened;
    opened.onDisconnect.addListener(() => {
      if (port === opened) port = null;
      browser.droidtopHiddenBootstrap.abortAll().catch(() => {});
    });
    opened.onMessage.addListener(async message => {
      try {
        if (message.op === "attest") {
          const reply = await browser.droidtopHiddenBootstrap.attest(message.profile, JSON.stringify(message.prefs));
          opened.postMessage(JSON.parse(reply));
          return;
        }
        if (message.op === "authorize") {
          await browser.droidtopHiddenBootstrap.authorize(message.requestId, message.allowed === true);
          return;
        }
        let result;
        if (message.op === "register") result = { registered: await browser.droidtopHiddenBootstrap.register(message.contextId, message.timeoutMs, JSON.stringify(message.proxy)) };
        else if (message.op === "unregister") { await browser.droidtopHiddenBootstrap.unregister(message.contextId); result = {}; }
        else if (message.op === "cookies") result = JSON.parse(await browser.droidtopHiddenBootstrap.cookies(message.contextId, message.cookieOp, message.host, message.header || ""));
        else if (message.op === "snapshot") {
          const info = JSON.parse(await browser.droidtopHiddenBootstrap.info(message.contextId));
          if (info.denied) result = { ...info, error: "permission_denied" };
          else {
            const tabId = await browser.droidtopHiddenBootstrap.tab(message.contextId);
            if (tabId < 0) result = { error: "document_not_ready" };
            else {
              // The file and target selection are fixed here, never supplied by a caller.
              const snapshots = await browser.tabs.executeScript(tabId, { file: "extract-document.js", allFrames: false, runAt: "document_idle" });
              result = { ...info, snapshot: snapshots[0] || { error: "document_not_ready" } };
            }
          }
        } else throw new Error("unsupported operation");
        opened.postMessage({ id: message.id, ok: true, result });
      } catch (error) {
        try { opened.postMessage({ id: message.id, ok: false }); } catch (closed) { /* Pending requests time out closed. */ }
      }
    });
  } catch (error) { port = null; }
}
setInterval(connect, 250);
let polling = false;
setInterval(async () => {
  if (!port || polling) return;
  polling = true;
  try {
    const requests = JSON.parse(await browser.droidtopHiddenBootstrap.pending());
    for (const request of requests) port?.postMessage({ op: "authorize", ...request });
  } catch (error) { /* The channel guard's own deadline cancels anything not answered. */ }
  finally { polling = false; }
}, 25);
connect();

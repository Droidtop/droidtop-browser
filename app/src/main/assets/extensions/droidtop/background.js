/* droidtop Browser's cookie bridge (droidtop docs/plugin-api.md 3 G3a). GeckoView has no cookie API of its own, so the
 * app asks this built-in extension over a native port to put droidtop's session into the private store before a page
 * opens, to read it back when the page ends, and to take it out again. Messages: {id, op, storeId, ...}; each answer is
 * {id, ok, result?, error?}. Nothing else is read or kept. */
"use strict";

let port = null;

/* Connects to the app, and keeps trying every second until it is connected: the app registers its handler after this
 * script starts, and a connectNative before that may throw or be dropped (on the console the port never opened, so the
 * page waited forever, 2026-11-22). The first message says hello, so the app knows the port is live. */
function connect() {
  if (port) return;
  try {
    const opened = browser.runtime.connectNative("droidtop");
    opened.onMessage.addListener(onMessage);
    opened.onDisconnect.addListener((p) => {
      console.log(`droidtop session: port closed ${(p && p.error && p.error.message) || ""}`);
      if (port === opened) port = null;
    });
    port = opened;
    port.postMessage({ hello: true });
    console.log("droidtop session: port opened");
  } catch (e) {
    console.log(`droidtop session: connectNative failed: ${e}`);
    port = null;
  }
}
setInterval(connect, 1000);

function answer(id, ok, result, error) {
  if (port) port.postMessage({ id, ok, result: result || null, error: error || null });
}

/* Only the built-in privileged experiment can address Gecko's session-context origin attribute. No operation
 * uses browser.cookies/firefox-private: that would address unrelated user private tabs. */
async function onMessage(message) {
  const { id, op, contextId } = message;
  if (!Number.isInteger(id)) return;
  try {
    const result = await browser.droidtopSessionCookies.call(contextId, op, JSON.stringify(message));
    answer(id, true, JSON.parse(result));
  } catch (e) {
    answer(id, false, null, String(e));
  }
}

connect();

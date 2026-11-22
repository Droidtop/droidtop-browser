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

/* The cookies the browser sends to https://<domain>/, as a Cookie header. */
async function header(domain, storeId) {
  const cookies = await browser.cookies.getAll({ url: `https://${domain}/`, storeId });
  return cookies.map((c) => `${c.name}=${c.value}`).join("; ");
}

async function clear(domain, storeId) {
  const cookies = await browser.cookies.getAll({ domain, storeId });
  for (const c of cookies) {
    const host = c.domain.replace(/^\./, "");
    await browser.cookies.remove({ url: `https://${host}${c.path}`, name: c.name, storeId });
  }
}

async function set(domain, text, storeId) {
  await clear(domain, storeId);
  for (const part of text.split(";")) {
    const pair = part.trim();
    const eq = pair.indexOf("=");
    if (eq <= 0) continue;
    await browser.cookies.set({
      url: `https://${domain}/`,
      name: pair.slice(0, eq),
      value: pair.slice(eq + 1),
      domain,
      path: "/",
      secure: true,
      storeId,
    });
  }
}

async function onMessage(message) {
  const { id, op, storeId } = message;
  try {
    if (op === "set") {
      for (const [domain, text] of Object.entries(message.cookies || {})) await set(domain, text, storeId);
      answer(id, true, {});
    } else if (op === "get") {
      const result = {};
      for (const domain of message.domains || []) result[domain] = await header(domain, storeId);
      answer(id, true, result);
    } else if (op === "clear") {
      for (const domain of message.domains || []) await clear(domain, storeId);
      answer(id, true, {});
    } else {
      answer(id, false, null, `unknown op ${op}`);
    }
  } catch (e) {
    answer(id, false, null, String(e));
  }
}

connect();

"use strict";
let port;
function connect() {
  if (port) return;
  try {
    const opened = browser.runtime.connectNative("droidtop-hidden");
    port = opened;
    opened.onDisconnect.addListener(() => { if (port === opened) port = null; });
    opened.onMessage.addListener(async message => {
      if (message.op !== "attest") return;
      try {
        const reply = await browser.droidtopHiddenBootstrap.attest(message.profile, JSON.stringify(message.prefs));
        opened.postMessage(JSON.parse(reply));
      } catch (error) { opened.postMessage({ ok: false }); }
    });
  } catch (error) { port = null; }
}
setInterval(connect, 250);
connect();

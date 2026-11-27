# Session lifetime decisions and static review, 2026-11-27

Owner request: "go through all the user-facing surfaces to see if they're usable" and include Droidtop Browser integration. Source-only audit; no local build or live browser/device interaction. Required recipe Android CI remains the compilation check.

## Bounded cookie bridge

`SessionCookies.send` previously retained every answer until a reply arrived. A connected-but-unresponsive extension or port disconnect could strand `WebViewActivity.finishWith`, which waits for its final cookie read before closing. Each request now answers once with a result or failure within ten seconds. A disconnected current port fails outstanding requests; late replies cannot call them again. A readiness timeout releases installation state so a later page can retry. This extends the existing ten-second installation deadline rather than adding a second unbounded wait.

## Cancellation and cleanup

The one-use `take_request` reply can arrive after its activity has been cancelled/destroyed. A valid late reply must be returned to Droidtop as incomplete instead of silently abandoning the token. A delayed extension-ready callback must not inject cookies into a cancelled activity. If injection was already sent when cancellation happened, successful completion removes the domains it installed. A failed injection ends the request incomplete rather than opening a page with an unknown session.

The cleanup domain set is shared between normal finish, destruction and injection cancellation: request domains plus download-site cookie keys. Previously destruction removed only request domains, leaving any injected download-site cookies behind.

## Boundaries and unverified gaps

- Host source: Droidtop `WebViewProviders.take` filters stored session cookies to request domains, adds approved download-site cookies, hands off a one-use token and imposes an overall ten-minute timeout. Its default-closed provider selection checks plugin approval and permission. Browser `DroidtopLink` pins Droidtop's signing certificate. The bridge runs only in Firefox's private cookie store.
- Native extension `background.js` already retries `connectNative` every second and says hello. Kotlin waits for `onPortConnected`, not a hello acknowledgement; actual Gecko handler registration/port sequencing needs a device trace. No speculative handshake change was made.
- Downloads are captured rather than saved, with per-resource cookie and user agent from Gecko's external-resource callback. The callback's provided response ownership/release behavior was not established from pinned upstream API source in this pass.
- The finish result reads `engine.settings.userAgentString`. Whether this is populated with Gecko's effective default identity on this pin is unverified. Reusing a stored WebView/Chrome identity in Gecko could invalidate browser checks; no guessed user-agent override was added.
- All these pages share Firefox's private cookie store. Concurrent page isolation and cleanup racing another page are not proven. This batch bounds failures but does not claim isolated cookie jars or a new session serialization mechanism.
- This activity handles many configuration changes in its manifest, but process death and unhandled configuration recreation have no restored one-use token/session ownership. The host eventually times out; recovery UX remains unresolved.
- Toolbar actions are horizontal, hardcoded English and not deeply reviewed for narrow screens, large fonts, pad focus or screen-reader semantics. Browser-check pass/fail behavior is a runtime question, not proven by compilation.

## Needs a rig check

Open a plugin sign-in/check/download page; verify session cookies enter only the intended sites and return with Gecko's effective user agent. Cancel before broker connection, during extension readiness and during cookie injection; verify immediate incomplete result, no reopened page and no leftover download-site cookies. Interrupt/disconnect the extension during a cookie read; Close must resolve within ten seconds. Retry afterward. Capture a protected download, verify captured headers and that no browser download was saved. Test two overlapping requests before claiming cookie isolation.

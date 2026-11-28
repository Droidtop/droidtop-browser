# Changelog

## Unreleased

- Browser previews and sign-in pages explain when a download needs Droidtop's download action.
- Downloads that use compression avoid failures caused by an advertised size that differs from the delivered body.

- Activity-based browser pages can transfer actual response bodies on Android 8 and later through an independently negotiated download API.

- Android 11 and later can provide a Browser-owned web view inside Droidtop through a scoped surface API, including navigation status and the existing session/download result boundary.
- Browse-only views use their own private Gecko context and exchange no saved cookies or downloads.

- Browser session operations now time out if their cookie extension stops answering, so closing a page can finish.
- Cancelling while a page opens returns its request and prevents delayed session injection. Cleanup includes download-site cookies.

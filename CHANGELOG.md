# Changelog

## Unreleased

- Android 11 and later can provide a Browser-owned web view inside Droidtop through a scoped surface API, including navigation status and the existing session/download result boundary.
- Browse-only views use their own private Gecko context and exchange no saved cookies or downloads.

- Browser session operations now time out if their cookie extension stops answering, so closing a page can finish.
- Cancelling while a page opens returns its request and prevents delayed session injection. Cleanup includes download-site cookies.

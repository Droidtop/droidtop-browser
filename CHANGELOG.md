# Changelog

## Unreleased

- Browser session operations now time out if their cookie extension stops answering, so closing a page can finish.
- Cancelling while a page opens returns its request and prevents delayed session injection. Cleanup includes download-site cookies.

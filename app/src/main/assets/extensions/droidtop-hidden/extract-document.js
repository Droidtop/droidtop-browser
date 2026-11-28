/* This packaged file is injected only after the privileged bridge resolves an exact registered private context.
 * No script, selector or form-capture expression is supplied by a caller. The result is document text, not wire bytes. */
(() => {
  "use strict";
  const MAX_BYTES = 131072;
  const failed = reason => ({ error: reason });
  if (window !== window.top) return failed("not_top_document");
  if ((document.characterSet || "").toLowerCase() !== "utf-8") return failed("unsupported_encoding");
  const contentType = (document.contentType || "").toLowerCase().split(";")[0].trim();
  const json = /^application\/(?:[a-z0-9!#$&^_.+-]+\+)?json$/.test(contentType);
  const html = contentType === "text/html" || contentType === "application/xhtml+xml";
  if (!json && !html && !contentType.startsWith("text/")) return failed("unsupported_document");
  // The owned runtime locks devtools.jsonview.enabled=false. JSON is therefore a plaintext document, not a
  // Firefox viewer whose body could include controls/labels. Do not add fallback selectors for viewer internals.
  const text = html ? document.documentElement?.outerHTML : document.body?.textContent;
  if (typeof text !== "string") return failed("document_not_ready");
  if (text.length > MAX_BYTES) return failed("document_too_large");
  if (text.includes("\uFFFD")) return failed("ambiguous_decoding");
  if (json) {
    try { JSON.parse(text); } catch (error) { return failed("invalid_json"); }
  }
  const bytes = new TextEncoder().encode(text);
  if (bytes.length > MAX_BYTES) return failed("document_too_large");
  return { url: location.href, title: (document.title || "").slice(0, 512), contentType,
    charset: "UTF-8", text, bodyBytes: bytes.length, truncated: false };
})();

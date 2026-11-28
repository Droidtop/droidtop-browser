const assert = require("node:assert/strict");
const fs = require("node:fs");
const path = require("node:path");
const vm = require("node:vm");
const source = fs.readFileSync(path.join(__dirname, "../app/src/main/assets/extensions/droidtop-hidden/extract-document.js"), "utf8");
function extract(contentType, text, charset = "UTF-8") {
  const window = {}; window.top = window;
  const context = { window, document: { contentType, characterSet: charset, title: "A page",
    body: { textContent: text }, documentElement: { outerHTML: text } }, location: { href: "https://example.com/page" }, TextEncoder };
  return vm.runInNewContext(source, context);
}
const original = '{\n  "message": "日本語 😀", "escaped": "a\\r\\nb", "nested": [true, null, 4]\n}';
const json = extract("application/json", original);
assert.equal(json.text, original);
assert.deepEqual(JSON.parse(json.text), JSON.parse(original));
assert.equal(json.bodyBytes, Buffer.byteLength(original));
assert.equal(json.truncated, false);
assert.equal(extract("application/problem+json", '{"error":true}').text, '{"error":true}');
assert.equal(extract("application/json", "{incomplete").error, "invalid_json");
assert.equal(extract("application/json", '{"x":"�"}').error, "ambiguous_decoding");
assert.equal(extract("application/json", '{}', "windows-1252").error, "unsupported_encoding");
assert.equal(extract("application/json", '"' + 'x'.repeat(131073) + '"').error, "document_too_large");
assert.equal(extract("application/json", '"' + '界'.repeat(60000) + '"').error, "document_too_large");
const challenge = '<html><head><title>Just a moment...</title></head><body>Checking</body></html>';
assert.equal(extract("text/html", challenge).text, challenge); // Canonical SiteCheck classifies it, never local regexes.
assert.equal(extract("text/html", '<html>' + 'x'.repeat(131073)).error, "document_too_large");
assert.equal(extract("application/octet-stream", 'data').error, "unsupported_document");
assert.equal(extract("text/plain", 'one\ntwo').text, 'one\ntwo');
console.log("Fixed hidden extractor: UTF8 JSON/text, HTML challenge fidelity, invalid/oversized refusal pass");

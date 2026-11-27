#!/usr/bin/env python3
"""droidtop Browser's side of the web.view contract (droidtop docs/contracts/web-view.json, plugin-api G3a), checked
statically before the build: droidtop checks its own side against the same file (WebViewProvidersContractTest).

Usage: check_contract.py <web-view.json> <repo root> <mytop-plugins/android/web-surface-v1.json>

Checks, from the adapter's Kotlin (app/src/main/java/dev/droidtop/browser) and droidtop/AndroidManifest.part.xml:
- the activity answers the contract's intent action, and the token extra is the contract's;
- the adapter calls the contract's api and every op it names, and no op the contract does not have;
- every key it sends (`.put("key"`) is an argument or download field of the contract;
- every key it reads (`.opt...("key")` / `.get...("key")`) is something droidtop sends (a reply key, a broker envelope
  key) or a field of a download it built itself;
- every argument of every op is sent somewhere.
Prints each problem and exits 1 when there is one.
"""
import json
import re
import sys
from pathlib import Path

# The broker's own envelope (a call's {api, version, op, args}, a reply's {ok, data|error{code, message}}), around every
# op of every contract; not part of web.view.
ENVELOPE = {"ok", "data", "error", "code", "message", "id", "result", "api", "version", "op", "args"}


def main() -> int:
    contract = json.loads(Path(sys.argv[1]).read_text(encoding="utf-8"))
    root = Path(sys.argv[2])
    sources = sorted((root / "app/src/main/java/dev/droidtop/browser").glob("*.kt"))
    if not sources:
        print("no adapter sources under app/src/main/java/dev/droidtop/browser")
        return 1
    kotlin = "\n".join(p.read_text(encoding="utf-8") for p in sources)
    manifest = (root / "droidtop/AndroidManifest.part.xml").read_text(encoding="utf-8")
    problems = []
    # The named embedded-view relay is separate from the broker's JSON API. Its bounded events are the only
    # additional JSON fields the adapter may send; neither surface handles nor script objects belong in JSON.
    surface_path = Path(sys.argv[3])
    surface_contract = json.loads(surface_path.read_text(encoding="utf-8"))
    event_fields = set(surface_contract["eventFields"]) | set(surface_contract["bodyFields"])
    for suffix, methods in (("Provider", surface_contract["provider"]), ("Host", surface_contract["host"])):
        path = surface_path.parent / f"aidl/dev/droidtop/pluginhost/IWebViewSurface{suffix}.aidl"
        if not path.exists():
            problems.append(f"missing embedded view {suffix} AIDL")
            continue
        aidl = path.read_text(encoding="utf-8")
        for method in methods:
            if not re.search(rf"\b{method}\s*\(", aidl):
                problems.append(f"missing embedded view {suffix}.{method}")

    action = contract["intent"]["action"]
    if f'android:name="{action}"' not in manifest:
        problems.append(f"the manifest does not answer {action}")
    extra = contract["intent"]["extraToken"]
    if f'"{extra}"' not in kotlin:
        problems.append(f"the adapter does not read the token extra {extra}")

    api = contract["api"]
    ops = contract["ops"]
    calls = re.findall(r'DroidtopLink\.call\(\s*"([^"]+)"\s*,\s*"([^"]+)"', kotlin)
    if not calls:
        problems.append("the adapter makes no broker call")
    for called_api, op in calls:
        if called_api != api:
            problems.append(f"calls api {called_api!r}, the contract's is {api!r}")
        if op not in ops:
            problems.append(f"calls op {op!r}, which the contract does not have")
    for op in ops:
        if (api, op) not in calls:
            problems.append(f"never calls {api}.{op}")

    args = {a for spec in ops.values() for a in spec["args"]}
    download = set(contract["download"])
    replies = {r for spec in ops.values() for r in spec["reply"]}
    sent = set(re.findall(r'\.put\(\s*"([^"]+)"', kotlin))
    # The cookie bridge's own messages to the built-in extension are not droidtop's contract.
    bridge = set(re.findall(r'\.put\(\s*"([^"]+)"', (root / "app/src/main/java/dev/droidtop/browser/SessionCookies.kt").read_text(encoding="utf-8")))
    for key in sorted(sent - bridge):
        if key not in args and key not in download and key not in ENVELOPE and key not in event_fields:
            problems.append(f"sends {key!r}, which no op of the contract takes")
    for key in sorted(args):
        if key not in sent:
            problems.append(f"never sends the argument {key!r}")

    read = set(re.findall(r'\.(?:opt|get)(?:String|Int|Long|Boolean|JSONObject|JSONArray|Double)?\(\s*"([^"]+)"', kotlin))
    for key in sorted(read):
        if key not in replies and key not in ENVELOPE and key not in download:
            problems.append(f"reads {key!r}, which droidtop never sends")

    for p in problems:
        print(f"web.view contract: {p}")
    if not problems:
        print(f"web.view contract: the adapter matches ({len(calls)} calls, {len(sent - bridge)} keys sent, {len(read)} keys read)")
    return 1 if problems else 0


if __name__ == "__main__":
    sys.exit(main())

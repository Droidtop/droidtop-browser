#!/usr/bin/env python3
"""Generates Firefox's WebCompat extension files the way Firefox's own build does, without mozbuild.

android-components' feature-webcompat copies the extension from <topsrcdir>/browser/extensions/webcompat and its
build-time files from <topobjdir>/mobile/android/webcompat_addon_generated_files into the APK's assets. Iceraven sets
both to its repository root, where neither exists, so its APK ships no extension and Gecko refused to load it ("Error
while loading 'resource://android/assets/extensions/webcompat/manifest.json' (NS_ERROR_FILE_NOT_FOUND)", console
logcat 2026-11-22 13:35). browser-build.yml fetches the extension at the Firefox tag matching version.txt; this runs
the extension's own codegen.py (mobile/android/moz.build: GenerateWebCompatAddonFiles) to write run.js and
injections/generated/ there.

codegen.py uses three mozbuild helpers; they are stood in for here with the same behaviour for what it calls.

Usage: webcompat.py <extension dir> <output dir>
"""
import ast
import os
import posixpath
import sys
import types
from fnmatch import fnmatch


class _Found:
    """A found file as mozpack's FileFinder gives it: readable, with its path."""

    def __init__(self, path):
        self.path = path

    def read(self):
        with open(self.path, encoding="utf-8") as f:
            return f.read()


class FileFinder:
    def __init__(self, base):
        self.base = base

    def find(self, pattern):
        if not os.path.isdir(self.base):
            return
        for root, _, files in os.walk(self.base):
            for name in sorted(files):
                full = os.path.join(root, name)
                rel = os.path.relpath(full, self.base).replace(os.sep, "/")
                if fnmatch(rel, pattern):
                    yield rel, _Found(full)


class FileAvoidWrite:
    """Writes the file only when its contents changed, creating its directory."""

    def __init__(self, path):
        self.path = path
        self.parts = []

    def write(self, text):
        self.parts.append(text)

    def __enter__(self):
        return self

    def __exit__(self, *exc):
        if exc[0] is not None:
            return False
        text = "".join(self.parts)
        if os.path.isfile(self.path):
            with open(self.path, encoding="utf-8") as f:
                if f.read() == text:
                    return False
        os.makedirs(os.path.dirname(self.path), exist_ok=True)
        with open(self.path, "w", encoding="utf-8") as f:
            f.write(text)
        return False


def _install_stand_ins():
    mozpack = types.ModuleType("mozpack")
    mozpack_path = types.ModuleType("mozpack.path")
    for name in ("join", "normpath", "basename", "dirname", "splitext"):
        setattr(mozpack_path, name, getattr(posixpath, name))
    mozpack_files = types.ModuleType("mozpack.files")
    mozpack_files.FileFinder = FileFinder
    mozpack.path = mozpack_path
    mozpack.files = mozpack_files
    mozbuild = types.ModuleType("mozbuild")
    mozbuild_util = types.ModuleType("mozbuild.util")
    mozbuild_util.FileAvoidWrite = FileAvoidWrite
    mozbuild.util = mozbuild_util
    sys.modules.update({
        "mozpack": mozpack,
        "mozpack.path": mozpack_path,
        "mozpack.files": mozpack_files,
        "mozbuild": mozbuild,
        "mozbuild.util": mozbuild_util,
    })


def _preprocessed_files(extension):
    """The list preprocessed_intervention_files.mozbuild names, which codegen.py checks the interventions against."""
    with open(os.path.join(extension, "preprocessed_intervention_files.mozbuild"), encoding="utf-8") as f:
        tree = ast.parse(f.read())
    for node in tree.body:
        if isinstance(node, ast.Assign) and any(getattr(t, "id", None) == "preprocessed_intervention_files" for t in node.targets):
            return list(ast.literal_eval(node.value))
    raise SystemExit("webcompat.py: preprocessed_intervention_files not found: Firefox changed the build")


def main():
    if len(sys.argv) != 3:
        raise SystemExit(__doc__)
    extension = os.path.abspath(sys.argv[1]).replace(os.sep, "/")
    output = os.path.abspath(sys.argv[2]).replace(os.sep, "/")
    if not os.path.isfile(os.path.join(extension, "manifest.json")):
        raise SystemExit(f"webcompat.py: no extension at {extension}")
    _install_stand_ins()
    sys.path.insert(0, extension)
    import codegen  # the extension's own generator

    os.makedirs(output, exist_ok=True)
    with open(posixpath.join(output, "run.js"), "w", encoding="utf-8") as out:
        codegen.generate_run_js_and_files(
            out,
            posixpath.join(extension, "run.js"),
            posixpath.join(extension, "data", "interventions"),
            *_preprocessed_files(extension),
        )
    generated = os.listdir(os.path.join(output, "injections", "generated"))
    print(f"webcompat.py: run.js and {len(generated)} generated interventions in {output}")


if __name__ == "__main__":
    main()

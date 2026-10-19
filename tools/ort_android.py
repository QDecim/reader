# -*- coding: utf-8 -*-
"""Prepare onnxruntime-android for use NEXT TO the sherpa-onnx AAR (stdlib only, Python 3.6+).

Problem: sherpa-onnx ships its own libonnxruntime.so (ORT 1.28.2, symbols tagged VERS_1.28.2).
The Maven onnxruntime-android JNI lib wants VERS_1.28.0 and cannot bind to it
("cannot locate symbol OrtGetApiBase"). Fix: give the Maven copy another file name
(same length, so the ELF is patched in place), and point the Maven JNI lib at it.

Usage: python ort_android.py <onnxruntime-android.aar> <app_dir>
Writes: <app_dir>/libs/onnxruntime-classes.jar
        <app_dir>/src/main/jniLibs/arm64-v8a/libortjruntime.so   (renamed libonnxruntime.so)
        <app_dir>/src/main/jniLibs/arm64-v8a/libonnxruntime4j_jni.so (NEEDED patched)
"""
import os
import sys
import zipfile

OLD = b"libonnxruntime.so"
NEW = b"libortjruntime.so"
assert len(OLD) == len(NEW)


def patch_name(data, what):
    """Replace every NUL-terminated occurrence of OLD by NEW (same length => ELF layout unchanged)."""
    key_old = OLD + b"\x00"
    key_new = NEW + b"\x00"
    n = data.count(key_old)
    if n == 0:
        raise SystemExit("ERROR: '%s' not found in %s" % (OLD.decode(), what))
    return data.replace(key_old, key_new), n


def main(aar, app):
    out_so = os.path.join(app, "src", "main", "jniLibs", "arm64-v8a")
    out_jar = os.path.join(app, "libs")
    os.makedirs(out_so, exist_ok=True)
    os.makedirs(out_jar, exist_ok=True)
    with zipfile.ZipFile(aar) as z:
        names = set(z.namelist())
        need = ["classes.jar", "jni/arm64-v8a/libonnxruntime.so", "jni/arm64-v8a/libonnxruntime4j_jni.so"]
        for n in need:
            if n not in names:
                raise SystemExit("ERROR: %s missing in AAR; has: %s" % (n, sorted(names)[:30]))
        with open(os.path.join(out_jar, "onnxruntime-classes.jar"), "wb") as f:
            f.write(z.read("classes.jar"))
        core, n1 = patch_name(z.read("jni/arm64-v8a/libonnxruntime.so"), "libonnxruntime.so")
        jni, n2 = patch_name(z.read("jni/arm64-v8a/libonnxruntime4j_jni.so"), "libonnxruntime4j_jni.so")
    with open(os.path.join(out_so, "libortjruntime.so"), "wb") as f:
        f.write(core)
    with open(os.path.join(out_so, "libonnxruntime4j_jni.so"), "wb") as f:
        f.write(jni)
    print("ORT prepared: libortjruntime.so (%d replacements), libonnxruntime4j_jni.so (%d), classes jar" % (n1, n2))


if __name__ == "__main__":
    if len(sys.argv) != 3:
        raise SystemExit(__doc__)
    main(sys.argv[1], sys.argv[2])

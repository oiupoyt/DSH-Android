# Vendored: sherpa-onnx 2.0.1 (Android arm64-v8a)

`sherpa-onnx-2.0.1-arm64.aar` is the upstream official release AAR, trimmed to the two things
this app needs: the Java/Kotlin API classes and the arm64-v8a native libraries.

- Source: <https://github.com/k2-fsa/sherpa-onnx/releases/download/v2.0.1/sherpa-onnx-2.0.1.aar>
- Upstream AAR: 49,441,341 bytes
- Vendored AAR: 12,515,647 bytes
- License: Apache-2.0 (see the upstream repository)

## Why a vendored AAR and not a Maven coordinate

There is no `com.k2fsa.sherpa.onnx` artifact on Maven Central (`numFound: 0` for that group),
so the AAR cannot be resolved from `google()` / `mavenCentral()`. Adding the upstream GitHub
release as an Ivy/Maven repository would make the build depend on `github.com` at configure
time and would not be reproducible offline. Vendoring one pinned file keeps the build
hermetic and the version visible in review.

## What was removed, and how to reproduce this file

The upstream AAR carries `armeabi-v7a`, `x86` and `x86_64` natives as well, which this app
does not ship (`abiFilters += "arm64-v8a"`). Trimming avoids ~19 MB of dead payload:

```sh
curl -LO https://github.com/k2-fsa/sherpa-onnx/releases/download/v2.0.1/sherpa-onnx-2.0.1.aar
python3 - <<'EOF'
import zipfile
src, out = "sherpa-onnx-2.0.1.aar", "sherpa-onnx-2.0.1-arm64.aar"
z, o = zipfile.ZipFile(src), zipfile.ZipFile(out, "w", zipfile.ZIP_DEFLATED)
for name in z.namelist():
    if name.startswith("jni/") and not name.startswith("jni/arm64-v8a/"):
        continue
    if name.startswith(("prefab/", "headers/")):
        continue
    o.writestr(name, z.read(name))
o.close()
EOF
```

## Files the app uses

| Entry | Purpose |
|---|---|
| `classes.jar` | `com.k2fsa.sherpa.onnx.*` — `KeywordSpotter`, `OnlineStream`, `FeatureConfig`, `OnlineModelConfig`, `OnlineTransducerModelConfig` |
| `jni/arm64-v8a/libonnxruntime.so` | ONNX Runtime |
| `jni/arm64-v8a/libsherpa-onnx-c-api.so` | sherpa-onnx C API |
| `jni/arm64-v8a/libsherpa-onnx-cxx-api.so` | C++ API |
| `jni/arm64-v8a/libsherpa-onnx-jni.so` | JNI bindings (`KeywordSpotter_newFromFile`, `decode`, `getResult`, …) |

The AAR's own `AndroidManifest.xml` declares no permissions and no components, so it merges
cleanly. Its natives need only system libraries (`libandroid.so`, `liblog.so`, `libm.so`,
`libdl.so`, `libc.so`) plus the bundled `libonnxruntime.so` — verified with `readelf -d`.

## Verified API surface

`KeywordSpotter` exposes keyword spotting, which is what this feature needs:

```text
KeywordSpotter(AssetManager?, KeywordSpotterConfig)
createStream() / createStream(keywords)
decode(stream) / isReady(stream) / getResult(stream): KeywordSpotterResult(keyword, tokens, timestamps)
reset(stream) / release()
```

`KeywordSpotterConfig(featConfig, modelConfig, maxActivePaths, keywordsFile, keywordsScore,
keywordsThreshold, numTrailingBlanks)`.

Passing a null `AssetManager` selects the `newFromFile` constructor, which is why the model
files are copied to `cacheDir` first (see `KeywordSpotter.kt`).

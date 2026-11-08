# Release CI

`.github/workflows/release.yml` builds and publishes the release binaries. `docs/RELEASE.md` is
the same flow done by hand; this is the automated equivalent.

## Triggers

| Trigger | Effect |
|---|---|
| push a tag `vX.Y.Z` | builds and **publishes** a Release, but only if the tag matches `versionName` |
| *Run workflow* (manual) | builds and uploads **artifacts only**, unless `publish` is checked |
| branch push / pull request | nothing — deliberately not configured |

Bump `versionCode` / `versionName` in `app/build.gradle.kts` yourself. CI never edits them;
a tag that disagrees with the manifest fails in the first job, before any build cost.

## Signing secrets

Four repository secrets are required for a publishable APK:

| Secret | Value |
|---|---|
| `KEYSTORE_BASE64` | `base64 -w0 release.jks` |
| `KEYSTORE_PASSWORD` | store password |
| `KEY_ALIAS` | key alias, e.g. `dsh-android` |
| `KEY_PASSWORD` | key password |

**Without them the workflow still builds**, but the Release is skipped with a warning. That is
intentional: the app's updater compares the signing certificate and deletes an APK that does
not match, so a debug- or freshly-keyed Release would be unusable rather than merely unsigned.
Use the **same** keystore the shipped app is signed with.

## Two inputs that are not in the repository

| Input | How CI gets it |
|---|---|
| native libs (`libnode.so`, `libdshengine.so`, `libdshnode.so`, …) | `native/prebuilt.sh fetch` — a release keyed by a hash of `native/build.sh` + patches + JNI sources. Falls back to a full `native/build.sh` source build if that tag is missing. |
| `engine.zip` | rebuilt by `js/build-engine.sh` (`npm ci` against the committed lockfile, then the committed patches), cached by a hash of `js/engine` + `js/patches` + `js/packages` + `js/scripts` |

Both are artifacts this project publishes itself; nothing is fetched from a third party that
could change underneath a build.

## What a run does

1. **gate** — reads the version, rejects a mismatched tag, checks whether signing is configured.
2. **build** — frees disk, sets up JDK 21 / Node 24.22.1 / the SDK platform and build-tools,
   fetches native libs, builds or restores `engine.zip`, runs `:app:testDebugUnitTest`,
   assembles and signs the release APK, verifies the signer, writes notes, and generates
   `update.json` via `tools/make-update-manifest.py`.
3. **publish** — creates or updates the Release with the APK and `update.json`, commits
   `update/latest.json` (the current manifest for the fallback URL), and purges jsDelivr.

Step 2's manifest step runs only for a release-signed APK: the generator enforces v1+v2+v3
signatures, and a debug build has no v1 because `minSdk 29` makes AGP skip it.

## Repository variables

| Variable | Effect |
|---|---|
| `COMMIT_UPDATE_MANIFEST=true` | lets a publish run commit `update/latest.json`. Left unset, the Release still gets its own `update.json` asset, but the manifest every installed app polls is left alone. |

Keep it unset while releases are signed with a test key: committing a manifest whose
`signerSha256` is not the shipped app's would point existing installs at an update they cannot
install.

## Notes

- `android-actions/setup-android` is not used: it shells out to `sdkmanager tools`, a package
  that no longer exists, and the action fails before any project step runs.
- `update.json`'s download URL is built with `--repo "$GITHUB_REPOSITORY"`. The tool's default
  is upstream `jackwener/DSH-Android`, which would point every updater at the wrong assets; the
  workflow asserts the URL after generation so a regression fails loudly.
- A manual run's artifacts are debug-signed and carry no `update.json`. That is expected: use
  them to inspect a build, not to distribute it.

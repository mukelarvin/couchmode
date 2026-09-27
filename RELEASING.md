# Releasing CouchMode

## One-time setup: the release keystore

This has to happen once, locally, before the release workflow can sign
anything. **Do this once and never regenerate it** — Obtainium (like Android
itself) refuses an in-place update if a new release is signed with a
different key than the last one the user installed. If this keystore is
lost, every future release breaks upgrades for anyone who already installed
CouchMode; the only fix is asking users to uninstall and reinstall.

1. Generate the keystore (requires a JDK — `keytool` ships with it):

   ```sh
   keytool -genkeypair -v \
     -keystore couchmode-release.keystore \
     -alias couchmode \
     -keyalg RSA -keysize 2048 -validity 10000
   ```

   You'll be prompted for a keystore password, your name/org details (can
   be anything), and a key password (can be the same as the keystore
   password).

2. **Back this file up somewhere durable that isn't just this machine** —
   a password manager's file storage, encrypted cloud storage, etc. It's
   already covered by `.gitignore` (`*.keystore`), so it will never
   accidentally get committed — which also means git isn't backing it up
   for you.

3. Base64-encode it, to paste into a GitHub secret:

   ```sh
   # Windows (PowerShell)
   [Convert]::ToBase64String([IO.File]::ReadAllBytes("couchmode-release.keystore")) | Set-Clipboard

   # macOS / Linux
   base64 -i couchmode-release.keystore | pbcopy   # macOS
   base64 -w0 couchmode-release.keystore | xclip    # Linux
   ```

4. In the GitHub repo: **Settings → Secrets and variables → Actions → New
   repository secret**. Create these four:

   | Secret name | Value |
   |---|---|
   | `RELEASE_KEYSTORE_BASE64` | output of step 3 |
   | `RELEASE_KEYSTORE_PASSWORD` | the keystore password from step 1 |
   | `RELEASE_KEY_ALIAS` | `couchmode` (or whatever alias you used) |
   | `RELEASE_KEY_PASSWORD` | the key password from step 1 |

## Cutting a release

1. Bump `versionCode` (must strictly increase) and `versionName` in
   `app/build.gradle.kts`.
2. Commit that change.
3. Tag it and push the tag:

   ```sh
   git tag v0.2.0
   git push origin v0.2.0
   ```

4. `.github/workflows/release.yml` picks up the tag push, builds a signed
   release APK, and attaches it to a GitHub Release automatically.
5. Anyone with CouchMode added in Obtainium sees the update on their next
   check.

Only a `v*` tag triggers a release — ordinary commits/pushes to `main` only
run `ci.yml` (an unsigned debug build + lint), so nothing gets published
accidentally.

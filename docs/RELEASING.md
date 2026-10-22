# Releasing K-Line Dash

K-Line Dash is distributed as a signed APK on GitHub Releases (sideload). This page is for the
maintainer: how to create the signing key once, and how to cut a release.

## Why the signing key matters

Android only installs an update over an installed app when both APKs are signed with the **same
key**. Sign one release with a different key and every user has to uninstall first, which deletes
their settings, tracks and trips. So:

- The release key is created **once** and used for every release, forever.
- **If the keystore is lost, users can no longer update.** Back it up (below) before the first release.
- A release signed with the debug key is only for testing on your own device. It cannot update a
  properly signed release, and the other way round.

## One-time setup: create the keystore

Keep the keystore **outside the repository** (for example in your home folder). `*.jks`,
`*.keystore` and `keystore.properties` are in `.gitignore`, but do not rely on that.

```bash
keytool -genkeypair -v \
  -keystore "$HOME/keys/kline-dash-release.jks" \
  -alias kline-dash \
  -keyalg RSA -keysize 4096 -validity 10000 \
  -storetype PKCS12
```

`keytool` ships with the JDK (on Windows: `C:\Program Files\Microsoft\jdk-17.0.20.101-hotspot\bin\keytool.exe`).
Create the folder first. It asks for a store password and the certificate details (name, country;
they appear in the certificate, so use what you are happy to publish). For a PKCS12 keystore the key
password is the store password; if it asks for a separate key password, press Enter to reuse it.

**Back it up now:** copy the `.jks` file to at least two places that are not this computer (a
password manager's file storage, an encrypted USB stick), and store the passwords in your password
manager. Without the file and the passwords there is no way to publish an update.

Write down the certificate fingerprint; users can compare it with the APK (`apksigner verify --print-certs`):

```bash
keytool -list -v -keystore "$HOME/keys/kline-dash-release.jks" -alias kline-dash | grep SHA256
```

## Local release builds

`./gradlew :app:assembleRelease` reads the signing key from, in this order:

1. the environment variables `KLD_KEYSTORE_FILE`, `KLD_KEYSTORE_PASSWORD`, `KLD_KEY_ALIAS`, `KLD_KEY_PASSWORD`
2. `keystore.properties` in the repository root (not committed):

```properties
storeFile=C:/Users/you/keys/kline-dash-release.jks
storePassword=...
keyAlias=kline-dash
keyPassword=...
```

When neither is complete, the build still succeeds but is signed with the **debug key** and prints
`WARNING: Release APK is signed with the DEBUG key`. That is fine for trying the release build
(R8 shrinking) on your own device; never publish it. The APK is `app/build/outputs/apk/release/app-release.apk`.

## One-time setup: GitHub secrets

The release workflow builds from four repository secrets
(GitHub: Settings > Secrets and variables > Actions > New repository secret):

| Secret | Value |
|---|---|
| `KLD_KEYSTORE_BASE64` | the keystore file, base64 encoded (one line) |
| `KLD_KEYSTORE_PASSWORD` | the store password |
| `KLD_KEY_ALIAS` | `kline-dash` (the alias used above) |
| `KLD_KEY_PASSWORD` | the key password (the same as the store password for PKCS12) |

With the GitHub CLI, from the folder that holds the keystore:

```bash
base64 -w0 kline-dash-release.jks | gh secret set KLD_KEYSTORE_BASE64 --repo stijnvandepol/CarPlayinfo
gh secret set KLD_KEYSTORE_PASSWORD --repo stijnvandepol/CarPlayinfo
gh secret set KLD_KEY_ALIAS --repo stijnvandepol/CarPlayinfo
gh secret set KLD_KEY_PASSWORD --repo stijnvandepol/CarPlayinfo
```

(`gh secret set NAME` without input prompts for the value.) On Windows PowerShell, the base64 value is
`[Convert]::ToBase64String([IO.File]::ReadAllBytes("kline-dash-release.jks"))`.

If a secret is missing, the workflow fails with a message that names it and publishes nothing; it
never falls back to the debug key.

## Cutting a release

1. **Bump the version** in `app/build.gradle.kts`: `versionMajor`, `versionMinor`, `versionPatch`.
   `versionName` is `major.minor.patch` and `versionCode` is `major * 10000 + minor * 100 + patch`
   (1.2.3 is 10203), so minor and patch stay below 100 and the code always increases with the version.
2. **Run the gate locally** and fix anything red:

   ```bash
   ./gradlew :obd-core:test :app:testDebugUnitTest :app:assembleDebug :app:assembleRelease :app:lintDebug
   ```
3. **Commit and tag** (the tag must be `v` plus the versionName, or the workflow stops):

   ```bash
   git commit -am "Release 1.0.1"
   git tag v1.0.1
   git push origin main v1.0.1
   ```
4. **The workflow** (`.github/workflows/release.yml`, on tags `v*`) checks the secrets and the tag,
   runs the tests, builds the signed APK, refuses to continue if it is signed with the debug key, and
   creates the GitHub release with:
   - `kline-dash-<version>.apk`
   - `kline-dash-<version>-mapping.txt.gz` (R8 mapping, to decode crash reports of this version)
   - `SHA256SUMS.txt`
5. **Check the result**: download the files and run `sha256sum -c SHA256SUMS.txt`, then
   `apksigner verify --print-certs kline-dash-<version>.apk` and compare the fingerprint with the one you wrote down.
   Install over the previous release on a device (`adb install -r`) to confirm the update works.

A wrong release is fixed by a new version, not by moving the tag: delete the GitHub release, fix,
bump the patch number and tag again.

## Reading a crash report from a release build

The app keeps a local crash log. Release stack traces are obfuscated; decode one with the matching
mapping and `retrace` (Android SDK `cmdline-tools`):

```bash
gunzip -k kline-dash-1.0.1-mapping.txt.gz
retrace kline-dash-1.0.1-mapping.txt crash.txt
```

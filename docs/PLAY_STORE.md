# Publishing Inkstone on Google Play

Play builds use package `art.inkstone`. The beta APKs use `art.inkstone.preview`, so the two install side by side.

## One-time setup: the upload key

1. Create the key. Run this from the `android/` folder and choose a strong password:

   ```bash
   keytool -genkeypair -v -keystore inkstone-upload.jks -alias upload -keyalg RSA -keysize 2048 -validity 10000
   ```

2. Create `android/keystore.properties`:

   ```properties
   storeFile=inkstone-upload.jks
   storePassword=YOUR_PASSWORD
   keyAlias=upload
   keyPassword=YOUR_PASSWORD
   ```

3. Back up `inkstone-upload.jks` and the password outside this folder, for example in a password manager.

Git ignores both files (`*.jks` and `android/keystore.properties`). Never commit them. If the key is lost, Google can reset it, but only after a support request.

## Build the bundle

```bash
bash scripts/build-play.sh
```

The script builds the native library, then a signed `bundleRelease`. It writes `releases/inkstone-X.Y-play.aab` and never overwrites an earlier file. Without `keystore.properties` the script stops.

## Each update

1. Raise `versionCode` and `versionName` in `android/app/build.gradle`. Play rejects a version code it has already seen.
2. Run `scripts/build-play.sh`.
3. In Play Console, upload the `.aab` to a track (Internal, Closed or Production) and add release notes.

## In Play Console (first release)

1. Create the app: name Inkstone, type App, Free.
2. Accept Play App Signing. Google holds the app signing key, and this upload key signs only your uploads.
3. Under App content, add:
   - the privacy policy URL
   - the Data safety form
   - the content rating questionnaire
   - the target audience
   - the ads declaration
4. Under Store listing, add the descriptions, a 512×512 icon, a 1024×500 feature graphic, and phone and tablet screenshots.
5. Personal accounts created after November 2023 must run a closed test with at least 12 testers for 14 days in a row. After that, apply for production access.

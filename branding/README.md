# Branding

Make the app your organization's in two steps, then rebuild with `android/build.sh`:

1. Replace `logo.png` with your logo: a square PNG at least 512x512, transparent or plain background.
   It becomes the app icon (Android adds padding and crops it to a circle or rounded square) and appears at the top
   of the home screen.
2. Edit `branding.properties`: the app name under the icon, and the icon's background colour.

To publish your own version on Google Play, also change `applicationId` in `android/app/build.gradle` so it doesn't
clash with other organizations' builds.

The default logo belongs to the **Heart of Wisconsin Pickleball Association** (HOWPA), who the app was first built for.
It is **not** covered by the MIT license: replace it with your own before distributing your build.

# android/dev-packshots (DEV ONLY)

Pack pictures for the dev-origin test builds, cropped from the owner's reference screenshot
`docs/ui-reference/sr/stock.webp` by `tools/packshots/crop_dev_packshots.py`. The three field apps add this folder to
their APK assets only when Gradle gets `-Paron.devPackshots=true`, which only the CI job that builds the release-signed
dev APKs against the dev origin passes; `release-app.yml` (production) never does, and the apps read the folder only when
`BuildConfig.DEV_PACKSHOTS` is true. Mapping: `dev-packshots/index.txt`. The admin-uploaded thumbnail always wins over
these. The real pack art comes from AKTCL (docs/runbooks/sku-pack-pictures.md).

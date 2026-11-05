# Satchel Android

Kotlin / Jetpack Compose port based on the sibling Satchel iOS working tree. Original Satchel assets and nine language tables are reused. This is an Android Studio project; the Gradle wrapper and application module are included.

## Implemented in source

- Local trips, favorites, search, suggested/copied packing lists, packing/return checks, items, journals, photos, next-time notes, common list and JSON export. The local scripted guide is not AI or live information.
- At startup, the client attempts the reference guest login and release configuration alongside an app-open test ad. A valid H5 route opens a WebView and WebSocket; failure or initial-content timeout falls back to the local journal. The Android platform value and backend compatibility are unverified.
- The socket handles the reference ad, product, status and restore requests and persists ad results for redelivery/ACK. Ads use Google's Android test IDs and UMP. H5/backend remains responsible for content quotas and reward application.
- Google Play A-side membership implements product lookup, purchase launch, pending handling, signature verification, active-entitlement query, acknowledgement and restore. A purchase ownership marker is saved before launch; unknown transactions are not acknowledged as A-side purchases. Existing active Play subscriptions can still unlock the local A-side. Some Android-specific messages remain in English.
- In-app legal drafts were adapted from the iOS drafts for Android and need operator contact details, review and publication. They are not final legal documents.

## Google Play setup

The locally selected subscription IDs are `satchel_monthly` and `satchel_yearly`. Create each with a matching one-month or one-year auto-renewing base plan, prices and regions in the Play Console. IDs, availability and prices have not been registered or verified. The package ID `com.satchel.traveljournal` is provisional until registered. Supply the app's Base64 licensing public key through the Gradle property `SATCHEL_PLAY_PUBLIC_KEY`; purchase is unavailable until it is configured. The client displays Play's returned price. A-side local signature checks do not replace secure backend verification.

## Still incomplete

**H5 Google Play purchase and backend fulfillment are not implemented.** The existing provider code establishes only an Apple-receipt contract, and no Android order/verification contract has been supplied. The app returns an explicit failure to H5 instead of starting a payment it cannot fulfill; restore remains pending. See [Android H5 integration](docs/ANDROID-H5-INTEGRATION.md) for the exact handoff and recovery requirements. Production AdMob IDs, consent messages, service configuration and the server's renewal/refund/revocation handling also require setup.

The local free limits are three trips, 30 referenced photos and one guide question per local calendar day; a verified active A-side Play membership removes them. Android and iOS local stores are separate; import or automatic migration is not implemented.

No build, automated tests, emulator/device run, visual comparison, live service, ad, WebSocket or Google Play purchase validation was performed. The project is not a verified identical reproduction or ready for release.

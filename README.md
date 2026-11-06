# Satchel Android

Kotlin / Jetpack Compose port based on the sibling Satchel iOS working tree. Original Satchel assets and nine language tables are reused. This Android Studio project includes its Gradle wrapper.

The local travel journal, guest login, original H5 WebView/WebSocket and Google test advertising are implemented in source. Android continues to use its existing development API and WebSocket environment. The H5 purchase path targets the Google purchase endpoint documented in that environment: the app saves a matching Google Play purchase, submits its token to the provider, checks the provider membership profile and acknowledges the Play purchase before reporting success. The original H5 and its provider are not modified. See [Android H5 integration](docs/ANDROID-H5-INTEGRATION.md) for the documented fields and unresolved provider behavior.

The selected subscription IDs are `satchel_monthly` and `satchel_yearly`; the package ID is `com.satchel.traveljournal`. These are proposed and have not been registered. Create matching monthly/yearly auto-renewing subscriptions and configure the app's Google Play licensing public key through `SATCHEL_PLAY_PUBLIC_KEY`. Prices shown by the app come from Play. Developer API credentials, provider product mapping and renewal/refund handling must be configured and verified when the Play account exists.

The development H5 snapshot determines its membership display from the provider profile, and its playback quota code did not appear to consume membership; the production H5 has not been inspected. An endpoint declaration alone does not prove that Google verification, H5 membership, playback/download privileges or provider entitlement updates work. A returned/confirmed purchase whose provider status is not yet confirmed remains pending; a request with an uncertain result is not re-submitted until its idempotency is established. Existing A-side and B-side purchase ownership records are separate, and unknown transactions are not acknowledged as B-side purchases.

The `server/` directory is an earlier standalone prototype retained for reference and is not connected to the Android app. AdMob uses test IDs; Android legal documents are drafts requiring review and operator contact details.

No build, automated tests, emulator/device run, visual comparison, backend deployment, live service integration, ad, WebSocket or Google Play purchase validation was performed. The project is not a verified identical reproduction or release-ready.

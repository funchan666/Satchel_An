# Satchel Android

Kotlin / Jetpack Compose port in progress, based on the current working tree of the sibling Satchel iOS project. The app uses the original Satchel artwork and translations from that project. Open this directory in Android Studio; the Gradle wrapper and application module are included.

## Scope implemented in source

- Local trips, favorites, search, suggested and copied packing lists, packing/return checks, item editing, journal entries, photos, next-time notes, common list and JSON export.
- Local, scripted travel-guide responses and the free allowance of one question per local calendar day. This is not an AI or live-information service.
- English and the original eight additional translation tables. Some Android-specific status messages still use English.
- Local file writes use AtomicFile; an unreadable journal disables writes to avoid replacing existing records. Android and iOS local stores are independent; import or automatic migration is not implemented.
- The iOS free limits of three trips and 30 referenced photos apply. The Google Play client enables unlimited local usage only after an active subscription returned by Play passes local signature verification. With no Play public key configured, purchases are unavailable and the free limits apply.

## Incomplete integration

- Google Play Billing source covers product lookup, purchase launch, pending purchases, signature verification, acknowledgement, active entitlement query and restore. Products are `satchel_monthly` and `satchel_yearly`, each requiring an active auto-renewing base plan with a matching one-month / one-year period. The client reads actual prices from Play. After creating the Play app, supply its Base64 licensing public key with the Gradle property `SATCHEL_PLAY_PUBLIC_KEY`; until then no purchase can be launched. These identifiers are selected locally and have not been registered or checked for availability. The package ID `com.satchel.traveljournal` is also provisional until registered.
- Local signature verification protects the local A-side only and is weaker than server verification. The online H5 purchase flow, server-side Play Developer API verification, entitlement synchronization, refunds, revocations and notifications are not implemented. Set up and verify these before release, particularly if the H5 grants paid rights.
- Startup guest login, release configuration, H5/WebSocket, ad/consent flows, and online entitlement integration are not ported. The iOS endpoints and Apple receipt contract do not establish their Android equivalents.
- Camera capture delegates to the system camera app; image selection uses the Android content picker. Neither path has been exercised on a device.
- The original iOS legal documents are included as reference material only and are not displayed as Android policies. Android-specific documents, operator contact details and review are still required.
- No build, automated tests, emulator, device, visual comparison, Google Play purchase, or backend verification has been performed. The implementation is not a release-ready or verified identical reproduction.

Source reference: `/Users/chengdupengyuyan/Fun/Satchel` working tree, including its existing uncommitted changes. The source project was not modified.

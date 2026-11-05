# Android H5 and payments integration status

This is an integration record and an open contract request, not an assertion that the provider supports these Android messages.

## Implemented in client source

- The guest-session and release-configuration calls follow the iOS source; `getV2` sends `platform=android` and the Android application ID. The same development host, release name, timezone and language fields are used. Whether the provider accepts these Android values is unconfirmed. The returned NB02 URL gets `deviceCode` and `appId` as in iOS. NB01 permits a purchase when missing/null/false, blocks it when true or unrecognized.
- WebView loads the returned URL and starts the authenticated WSS relay. Initial content failure/30 foreground seconds falls back to the local journal. No JavaScript native bridge is added. The manifest blocks cleartext traffic, including HTTP addresses.
- Relay requests are limited to the iOS actions and validated against the current visitor, version and request ID. Ad requests persist before presentation; duplicate IDs replay the recorded result, and an interrupted ad is never treated as a reward. `APP_RESULT` stays in the iOS outer-field shape. Results requiring an ACK remain in the local ledger and are resent after reconnect; ambiguous ACKs do not clear later statuses. Socket message compatibility and delivery have not been tested with the provider.
- `subscription.getStatus` queries the provider user endpoint. `subscription.getProducts` returns actual Google Play product details when available. `subscription.restore` asks for provider status and returns `pending` until provider/Play ownership can be reconciled. H5/backend owns video/download quotas and deciding whether to grant ad rewards.
- App-open, interstitial and rewarded ads use Google Android sample/test IDs. UMP consent is requested first and privacy options are available when required. `rewarded` is returned only after the SDK's reward callback.

## Required before H5 purchasing can work

The iOS backend contract creates an order with `source=ios` and confirms it with an Apple receipt. The provider has supplied no Android order or verification contract. Consequently, `pay` and `subscription.purchase` return `failed` with `android_verification_unavailable` (or a more specific disabled/invalid-plan error); they do not launch Google Play or create an order. This prevents charging a user without a way to grant the H5 entitlement. A Play public key alone does not remove this restriction.

The provider must specify and implement:

1. Android release configuration and WebSocket authentication/forwarding formats and Android product IDs.
2. Idempotent order creation, including the accepted platform, product ID, user association, price/currency and retry behavior.
3. Verification of Google Play purchase tokens using the Google Play Developer API, including package, subscription product/base plan, entitlement state, replay protection and order association. The server must say precisely what fields constitute a successful verification.
4. Safe acknowledgement ownership, recovery after app/network interruption, reinstallation or device change, and backend updates for renewals, cancellations, expiration, refunds and revocations.
5. `subscription.getStatus`, product, restore and `APP_RESULT`/ACK message formats for Android and confirmation that the H5 only grants access based on the authoritative server state.

The app must persist the user/request/order and purchase attribution before launch, match the returned purchase token to the recorded order, retry uncertain verification without creating another purchase, and report `purchased` only after verified provider fulfillment. Unknown transactions must remain unacknowledged for reconciliation; the local A-side must not claim B-side transactions.

The A-side Play client is separately implemented with local signature checks and a device-local ownership marker for acknowledgement; this does not provide the provider's B-side entitlement. It is not a replacement for secure server verification. Nothing in this document establishes transaction, refund, ad, relay or real-device success.

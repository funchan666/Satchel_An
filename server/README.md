# Satchel Play subscription adapter

This is a proposed **separate service** for Android B-side purchases. It has not been deployed, run, tested with Play or connected to the existing H5/provider. It does not write into the provider's database or modify the provider's `rightExpireTime`. The H5 and provider must explicitly use this service's entitlement, or use the app's merged `subscription.getStatus` result, before access actually changes.

## What it does

- Authenticates each app request by calling the existing provider's user endpoint with the caller's bearer token. It extracts the immutable visitor ID from a configured field and fails closed when the response does not match the configured contract.
- Creates an idempotent order for a visitor/request and returns opaque Google Play account/profile identifiers. It verifies that Google's signed-in purchase information contains those identifiers and the expected product before associating the token with that order.
- Calls Google Play Developer API `purchases.subscriptionsv2.get`; accepts active, grace-period and cancelled-but-not-expired subscriptions only until the returned expiry. Locks and uniqueness constraints prevent a token being claimed by another user/order; linked prior subscriptions are retired for the same owner.
- Stores encrypted tokens in PostgreSQL, persists entitlement before acknowledging through Google, and retries acknowledgement on the next verification/status/notification. Returns `purchased` only after the entitlement has been recorded and acknowledgement is confirmed or succeeds. Pending, failed or unverified purchases do not get rights.
- Provides authenticated entitlement lookup and recovery of a purchase whose original order is still in this database. The RTDN push endpoint checks Google OIDC audience and service-account identity, then refreshes known subscription or voided-purchase tokens. Entitlement queries also refresh known subscriptions with Play. Unknown out-of-app resubscriptions and transfers without a matching order/profile need separate reconciliation; they are not automatically credited.

## Configuration required before use

1. Provision durable PostgreSQL and apply `schema.sql` once to the intended empty service database. This service does not alter the existing provider's database. Back up the database and encryption keys together. Run the service behind HTTPS, with restricted database access and a production ASGI server. `requirements.txt` specifies runtime dependencies; no deployment has been performed here.
2. Set `DATABASE_URL`, `TOKEN_ENCRYPTION_KEY` (Fernet key kept in a secret manager), and a stable random `ACCOUNT_HMAC_KEY` of at least 32 bytes. Changing or losing these keys can prevent attribution or recovery of existing orders; plan key rotation before production. Do not put secrets in the app or source control.
3. Set `PROVIDER_USER_URL` to the HTTPS authenticated user endpoint and `PROVIDER_USER_ID_FIELD` to the verified immutable visitor-ID field within its `data` object. Confirm that this field is exactly the `userId` returned by guest login, across token refresh. The app checks the returned owner against its own logged-in visitor ID before opening Play or accepting entitlement. The provider's actual response field has not been established by the iOS source.
4. Configure Google Application Default Credentials for an identity with permission to use the Play Developer API for the actual app. Set `PLAY_PACKAGE_NAME` if the registered package differs from the current default. Register/activate `satchel_monthly` and `satchel_yearly`, with matching monthly/yearly auto-renewing base plans.
5. Configure Play RTDN via Pub/Sub with an authenticated push subscription. Set `PUBSUB_AUDIENCE` to the exact configured audience and `PUBSUB_SERVICE_ACCOUNT` to the expected verified email. Ensure the service can reach the provider and Google, and Pub/Sub can reach `/v1/play-notifications`. The endpoint expects a Google-signed OIDC token. Protect secrets and avoid logging purchase tokens, guest tokens, query strings or authorization headers in your proxy and telemetry.
6. Configure Android build properties `SATCHEL_PLAY_BACKEND_URL` as the service's HTTPS base URL and `SATCHEL_PLAY_PUBLIC_KEY` as the Google Play app licensing public key. An absent or non-HTTPS backend URL keeps H5 purchases disabled. After the provider identity contract and H5 entitlement consumer have been verified, explicitly set the build property `SATCHEL_PLAY_H5_ENTITLEMENTS_READY=true`; it defaults to false so configuring a service alone cannot start purchases before H5 rights are connected.

## API shape

All `/v1/orders`, `/v1/verify`, `/v1/recover`, and `/v1/entitlement` requests carry the provider bearer token. The service independently derives `ownerId`; it never trusts a caller-supplied visitor ID.

- `POST /v1/orders`: `{requestId, productId, action}` → `{ownerId, orderId, accountId, profileId}`.
- `POST /v1/verify`: `{orderId, purchaseToken}` → `{ownerId, status, productId, rightExpireTime}`. Repeating the same order/token retries verification and acknowledgement without issuing another purchase.
- `POST /v1/recover`: `{purchaseToken}` → matching original order/request and current verified status, only for the authenticated owner and stored account/profile attribution.
- `GET /v1/entitlement`: → `{ownerId, active, rightExpireTime}` after refreshing known subscriptions. `rightExpireTime` is epoch milliseconds or null.
- `POST /v1/play-notifications`: authenticated Pub/Sub push. Pub/Sub retries non-success responses; Google subscription state is retrieved again rather than inferred from notification type.

The Android app merges an active adapter expiry with the provider's current `rightExpireTime` in the socket status response. That only helps an H5 that actually reads this response. It does not automatically change server-enforced H5 access. The provider must either integrate the adapter into its own entitlement check or agree on another authenticated server-to-server integration. Never use the relay's `purchased` message alone as proof of membership.

Before release, validate purchase, pending approval, cancellation, retries, restored orders, cross-device identity, expired tokens, renewals, refunds, revocations, RTDN authentication/redelivery and H5 authorization against real Play test purchases. The existing iOS relay's authentication and ACK behavior also remain unverified. No compilation or tests were performed here.

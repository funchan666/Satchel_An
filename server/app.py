"""Google Play subscription verification for Satchel's proposed Android H5 adapter.

Run behind HTTPS. The upstream provider must expose an authenticated user endpoint
whose immutable user-id field is configured; the H5 must consume this service's status.
"""
import base64
import hashlib
import hmac
import json
import os
import uuid
from datetime import datetime, timezone
from urllib.parse import quote, urlparse

import google.auth
from google.auth.transport.requests import AuthorizedSession, Request as GoogleRequest
from google.oauth2 import id_token
import httpx
import psycopg
from cryptography.fernet import Fernet, InvalidToken
from fastapi import FastAPI, Header, HTTPException, Request
from starlette.concurrency import run_in_threadpool
from pydantic import BaseModel, Field
from psycopg.rows import dict_row

PACKAGE = os.environ.get("PLAY_PACKAGE_NAME", "com.satchel.traveljournal")
PRODUCTS = {"satchel_monthly", "satchel_yearly"}
ACTIVE = {"SUBSCRIPTION_STATE_ACTIVE", "SUBSCRIPTION_STATE_IN_GRACE_PERIOD", "SUBSCRIPTION_STATE_CANCELED"}
SCOPE = "https://www.googleapis.com/auth/androidpublisher"
app = FastAPI(docs_url=None, redoc_url=None, openapi_url=None)


def required(name: str) -> str:
    value = os.environ.get(name, "").strip()
    if not value:
        raise HTTPException(503, "service_not_configured")
    return value


def database():
    return psycopg.connect(required("DATABASE_URL"), row_factory=dict_row)


def cipher():
    try:
        return Fernet(required("TOKEN_ENCRYPTION_KEY").encode("ascii"))
    except (ValueError, TypeError, UnicodeError):
        raise HTTPException(503, "service_not_configured")


def provider_identity(authorization: str | None) -> str:
    if not authorization or not authorization.startswith("Bearer ") or len(authorization) > 8192:
        raise HTTPException(401, "unauthorized")
    url = required("PROVIDER_USER_URL")
    parsed = urlparse(url)
    if parsed.scheme != "https" or not parsed.hostname or parsed.username or parsed.password:
        raise HTTPException(503, "service_not_configured")
    try:
        with httpx.Client(timeout=10, follow_redirects=False) as client:
            response = client.get(url, headers={"Authorization": authorization, "Accept": "application/json"})
        if response.status_code in (401, 403):
            raise HTTPException(401, "unauthorized")
        response.raise_for_status()
        payload = response.json()
        if not isinstance(payload, dict) or payload.get("code") != 0:
            raise HTTPException(401, "unauthorized")
        data = payload.get("data")
        if not isinstance(data, dict):
            raise HTTPException(503, "provider_identity_unavailable")
        field = required("PROVIDER_USER_ID_FIELD")
        value = data.get(field)
        if isinstance(value, bool) or not isinstance(value, (str, int)) or not str(value).strip():
            raise HTTPException(503, "provider_identity_unavailable")
        return str(value)
    except HTTPException:
        raise
    except (httpx.HTTPError, ValueError):
        raise HTTPException(503, "provider_identity_unavailable")


def account_id(owner: str) -> str:
    key = required("ACCOUNT_HMAC_KEY").encode()
    if len(key) < 32:
        raise HTTPException(503, "service_not_configured")
    return hmac.new(key, owner.encode(), hashlib.sha256).hexdigest()


def token_hash(token: str) -> str:
    return hashlib.sha256(token.encode()).hexdigest()


def google_session():
    try:
        credentials, _ = google.auth.default(scopes=[SCOPE])
        return AuthorizedSession(credentials)
    except Exception:
        raise HTTPException(503, "play_credentials_unavailable")


def get_subscription(token: str) -> dict:
    url = ("https://androidpublisher.googleapis.com/androidpublisher/v3/applications/"
           + quote(PACKAGE, safe="") + "/purchases/subscriptionsv2/tokens/" + quote(token, safe=""))
    try:
        response = google_session().get(url, timeout=15)
    except Exception:
        raise HTTPException(503, "play_unavailable")
    if response.status_code == 410:
        raise HTTPException(422, "play_purchase_expired")
    if response.status_code in (400, 404):
        raise HTTPException(422, "play_purchase_unavailable")
    if not response.ok:
        raise HTTPException(503, "play_unavailable")
    try:
        result = response.json()
        if not isinstance(result, dict):
            raise ValueError()
        return result
    except ValueError:
        raise HTTPException(503, "play_invalid_response")


def ack_subscription(product: str, token: str) -> None:
    url = ("https://androidpublisher.googleapis.com/androidpublisher/v3/applications/" + quote(PACKAGE, safe="")
           + "/purchases/subscriptions/" + quote(product, safe="") + "/tokens/" + quote(token, safe="") + ":acknowledge")
    try:
        response = google_session().post(url, json={}, timeout=15)
    except Exception:
        raise HTTPException(503, "play_acknowledgement_pending")
    if not response.ok:
        raise HTTPException(503, "play_acknowledgement_pending")



def ensure_ack(product: str, token: str, ack: str) -> None:
    if ack == "ACKNOWLEDGEMENT_STATE_PENDING":
        ack_subscription(product, token)
    elif ack != "ACKNOWLEDGEMENT_STATE_ACKNOWLEDGED":
        raise HTTPException(503, "play_acknowledgement_pending")

def expiry(value: str | None) -> datetime | None:
    if not isinstance(value, str):
        return None
    try:
        dt = datetime.fromisoformat(value.replace("Z", "+00:00"))
        return dt if dt.tzinfo is not None else None
    except ValueError:
        return None


def verified_fields(result: dict, order: dict) -> tuple[str, datetime | None, str]:
    external = result.get("externalAccountIdentifiers") or {}
    if (external.get("obfuscatedExternalAccountId") != order["account_id"] or
            external.get("obfuscatedExternalProfileId") != order["profile_id"]):
        raise HTTPException(409, "purchase_owner_mismatch")
    items = [x for x in result.get("lineItems", []) if isinstance(x, dict) and x.get("productId") == order["product_id"]]
    if not items:
        raise HTTPException(409, "purchase_product_mismatch")
    dates = [d for d in (expiry(item.get("expiryTime")) for item in items) if d is not None]
    state = result.get("subscriptionState", "SUBSCRIPTION_STATE_UNSPECIFIED")
    return state, max(dates) if dates else None, result.get("acknowledgementState", "ACKNOWLEDGEMENT_STATE_UNSPECIFIED")


def is_active(state: str, expires: datetime | None) -> bool:
    return state in ACTIVE and expires is not None and expires > datetime.now(timezone.utc)


def lock_token(conn, digest: str):
    # Serializes verification and RTDN refreshes for a token across workers.
    number = int(digest[:16], 16)
    if number >= 2**63:
        number -= 2**64
    conn.execute("SELECT pg_advisory_xact_lock(%s)", (number,))


def sync_purchase(conn, order: dict, token: str) -> tuple[str, datetime | None, str]:
    digest = token_hash(token)
    lock_token(conn, digest)
    known = conn.execute("SELECT owner, order_id, state FROM play_subscriptions WHERE token_hash=%s FOR UPDATE", (digest,)).fetchone()
    if known and (known["owner"] != order["owner"] or known["order_id"] != order["id"]):
        raise HTTPException(409, "purchase_already_claimed")
    if known and known["state"] == "REPLACED":
        raise HTTPException(409, "purchase_replaced")
    result = get_subscription(token)
    state, expires, ack = verified_fields(result, order)
    linked = result.get("linkedPurchaseToken")
    if isinstance(linked, str) and linked:
        previous = conn.execute("SELECT owner FROM play_subscriptions WHERE token_hash = %s FOR UPDATE", (token_hash(linked),)).fetchone()
        if previous and previous["owner"] != order["owner"]:
            raise HTTPException(409, "linked_purchase_owner_mismatch")
        if previous and is_active(state, expires):
            conn.execute("UPDATE play_subscriptions SET state = 'REPLACED', expires_at = now(), updated_at = now() WHERE token_hash = %s", (token_hash(linked),))
    encrypted = cipher().encrypt(token.encode()).decode()
    row = conn.execute("""INSERT INTO play_subscriptions
        (token_hash, token_ciphertext, owner, order_id, product_id, state, expires_at, ack_state)
        VALUES (%s,%s,%s,%s,%s,%s,%s,%s)
        ON CONFLICT (token_hash) DO UPDATE SET state=EXCLUDED.state, expires_at=EXCLUDED.expires_at,
          ack_state=EXCLUDED.ack_state, updated_at=now()
        WHERE play_subscriptions.owner=EXCLUDED.owner AND play_subscriptions.order_id=EXCLUDED.order_id
        RETURNING token_hash""", (digest, encrypted, order["owner"], order["id"], order["product_id"], state, expires, ack)).fetchone()
    if not row:
        raise HTTPException(409, "purchase_already_claimed")
    return state, expires, ack


class OrderRequest(BaseModel):
    requestId: str = Field(min_length=1, max_length=128)
    productId: str = Field(min_length=1, max_length=40)
    action: str = Field(min_length=1, max_length=40)


class RecoverRequest(BaseModel):
    purchaseToken: str = Field(min_length=1, max_length=4096)


class VerifyRequest(BaseModel):
    orderId: uuid.UUID
    purchaseToken: str = Field(min_length=1, max_length=4096)


@app.post("/v1/orders")
def create_order(body: OrderRequest, authorization: str | None = Header(default=None)):
    owner = provider_identity(authorization)
    if body.productId not in PRODUCTS or body.action not in {"pay", "subscription.purchase"}:
        raise HTTPException(400, "invalid_product")
    with database() as conn:
        row = conn.execute("""INSERT INTO play_orders(id, owner, request_id, product_id, action, account_id, profile_id)
          VALUES (%s,%s,%s,%s,%s,%s,%s) ON CONFLICT (owner,request_id) DO NOTHING RETURNING *""",
          (uuid.uuid4(), owner, body.requestId, body.productId, body.action, account_id(owner), uuid.uuid4().hex)).fetchone()
        if not row:
            row = conn.execute("SELECT * FROM play_orders WHERE owner=%s AND request_id=%s", (owner, body.requestId)).fetchone()
        if not row or row["product_id"] != body.productId or row["action"] != body.action:
            raise HTTPException(409, "request_conflict")
    return {"ownerId": owner, "orderId": str(row["id"]), "accountId": row["account_id"], "profileId": row["profile_id"]}


@app.post("/v1/verify")
def verify(body: VerifyRequest, authorization: str | None = Header(default=None)):
    owner = provider_identity(authorization)
    with database() as conn:
        order = conn.execute("SELECT * FROM play_orders WHERE id=%s AND owner=%s FOR UPDATE", (body.orderId, owner)).fetchone()
        if not order:
            raise HTTPException(404, "order_not_found")
        try:
            state, expires, ack = sync_purchase(conn, order, body.purchaseToken)
        except psycopg.errors.UniqueViolation:
            raise HTTPException(409, "order_already_has_purchase")
    active = is_active(state, expires)
    if active:
        ensure_ack(order["product_id"], body.purchaseToken, ack)
    return {"ownerId": owner, "status": "purchased" if active else "pending", "productId": order["product_id"], "rightExpireTime": int(expires.timestamp() * 1000) if expires else None}



@app.post("/v1/recover")
def recover(body: RecoverRequest, authorization: str | None = Header(default=None)):
    owner = provider_identity(authorization)
    purchase = get_subscription(body.purchaseToken)
    external = purchase.get("externalAccountIdentifiers") or {}
    if external.get("obfuscatedExternalAccountId") != account_id(owner):
        raise HTTPException(409, "purchase_owner_mismatch")
    profile = external.get("obfuscatedExternalProfileId")
    if not isinstance(profile, str) or not profile:
        raise HTTPException(409, "purchase_owner_mismatch")
    with database() as conn:
        order = conn.execute("SELECT * FROM play_orders WHERE owner=%s AND profile_id=%s FOR UPDATE", (owner, profile)).fetchone()
        if not order:
            raise HTTPException(404, "order_not_found")
        try:
            state, expires, ack = sync_purchase(conn, order, body.purchaseToken)
        except psycopg.errors.UniqueViolation:
            raise HTTPException(409, "order_already_has_purchase")
    active = is_active(state, expires)
    if active:
        ensure_ack(order["product_id"], body.purchaseToken, ack)
    return {"ownerId": owner, "orderId": str(order["id"]), "requestId": order["request_id"], "action": order["action"],
            "productId": order["product_id"], "accountId": order["account_id"], "profileId": order["profile_id"],
            "status": "purchased" if active else "pending", "rightExpireTime": int(expires.timestamp() * 1000) if expires else None}

def refresh_existing(row: dict) -> None:
    try:
        token = cipher().decrypt(row["token_ciphertext"].encode()).decode()
    except (InvalidToken, UnicodeError):
        raise HTTPException(503, "purchase_storage_unavailable")
    with database() as conn:
        order = conn.execute("SELECT * FROM play_orders WHERE id=%s FOR UPDATE", (row["order_id"],)).fetchone()
        if not order:
            raise HTTPException(503, "purchase_storage_unavailable")
        try:
            state, expires, ack = sync_purchase(conn, order, token)
        except HTTPException as error:
            if error.detail == "purchase_replaced":
                return
            if error.detail != "play_purchase_expired":
                raise
            conn.execute("UPDATE play_subscriptions SET state='SUBSCRIPTION_STATE_EXPIRED', expires_at=now(), updated_at=now() WHERE token_hash=%s", (row["token_hash"],))
            return
    if is_active(state, expires):
        ensure_ack(order["product_id"], token, ack)


@app.get("/v1/entitlement")
def entitlement(authorization: str | None = Header(default=None)):
    owner = provider_identity(authorization)
    with database() as conn:
        rows = conn.execute("SELECT * FROM play_subscriptions WHERE owner=%s AND state <> 'REPLACED'", (owner,)).fetchall()
    for row in rows:
        refresh_existing(row)
    with database() as conn:
        rows = conn.execute("SELECT state, expires_at FROM play_subscriptions WHERE owner=%s AND state <> 'REPLACED'", (owner,)).fetchall()
    dates = [r["expires_at"] for r in rows if is_active(r["state"], r["expires_at"])]
    return {"ownerId": owner, "active": bool(dates), "rightExpireTime": int(max(dates).timestamp() * 1000) if dates else None}


def authenticate_notification(authorization: str | None) -> None:
    if not authorization or not authorization.startswith("Bearer "):
        raise HTTPException(401, "unauthorized")
    try:
        claims = id_token.verify_oauth2_token(authorization[7:], GoogleRequest(), audience=required("PUBSUB_AUDIENCE"))
        if claims.get("email") != required("PUBSUB_SERVICE_ACCOUNT") or claims.get("email_verified") is not True:
            raise HTTPException(403, "unauthorized")
    except HTTPException:
        raise
    except Exception:
        raise HTTPException(401, "unauthorized")


def process_notification(payload: dict) -> None:
    if payload.get("packageName") != PACKAGE:
        raise HTTPException(400, "invalid_notification")
    notice = payload.get("subscriptionNotification")
    if not isinstance(notice, dict):
        voided = payload.get("voidedPurchaseNotification")
        notice = voided if isinstance(voided, dict) and voided.get("productType") == 1 else None
    if not isinstance(notice, dict):
        return
    token = notice.get("purchaseToken")
    if not isinstance(token, str) or not 0 < len(token) <= 4096:
        raise HTTPException(400, "invalid_notification")
    with database() as conn:
        row = conn.execute("SELECT * FROM play_subscriptions WHERE token_hash=%s", (token_hash(token),)).fetchone()
    if row:
        refresh_existing(row)


@app.post("/v1/play-notifications")
async def notifications(request: Request, authorization: str | None = Header(default=None)):
    await run_in_threadpool(authenticate_notification, authorization)
    content = bytearray()
    async for chunk in request.stream():
        content.extend(chunk)
        if len(content) > 65536:
            raise HTTPException(413, "invalid_notification")
    try:
        envelope = json.loads(content)
        encoded = envelope.get("message", {}).get("data", "")
        if len(encoded) > 32768:
            raise ValueError()
        payload = json.loads(base64.b64decode(encoded, validate=True))
        if not isinstance(payload, dict):
            raise ValueError()
    except (ValueError, TypeError, AttributeError):
        raise HTTPException(400, "invalid_notification")
    await run_in_threadpool(process_notification, payload)
    return {"received": True}

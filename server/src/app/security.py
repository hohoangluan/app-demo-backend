"""Password, OTP, and session-token hashing primitives for the demo auth subsystem.

Passwords and OTP codes are low-entropy secrets a user chooses or receives, so
they are hashed with salted PBKDF2-HMAC-SHA256 (slow, brute-force resistant).
Session tokens are generated server-side with ``secrets.token_urlsafe``,
already high-entropy, so they only need a fast SHA-256 digest for
constant-time lookup by hash -- mirrors the existing Bearer-token digest
pattern in ``app/auth.py``.
"""

from __future__ import annotations

import hashlib
import hmac
import secrets

_PBKDF2_ALGORITHM = "sha256"
_PBKDF2_ITERATIONS = 200_000
_SALT_BYTES = 16
_OTP_DIGITS = 6
_STORED_SECRET_PREFIX = "pbkdf2_sha256"  # noqa: S105 -- a hash-format tag, not a credential
_STORED_SECRET_PART_COUNT = 4


def _pbkdf2_digest(secret: str, salt: bytes, iterations: int) -> bytes:
    """Derive the raw PBKDF2-HMAC-SHA256 digest for ``secret``."""
    return hashlib.pbkdf2_hmac(_PBKDF2_ALGORITHM, secret.encode("utf-8"), salt, iterations)


def hash_secret(secret: str) -> str:
    """Hash a low-entropy secret (password or OTP code) for storage.

    Returns a self-describing ``pbkdf2_sha256$<iterations>$<salt_hex>$<digest_hex>``
    string so :func:`verify_secret` never needs out-of-band parameters.
    """
    salt = secrets.token_bytes(_SALT_BYTES)
    digest = _pbkdf2_digest(secret, salt, _PBKDF2_ITERATIONS)
    return f"{_STORED_SECRET_PREFIX}${_PBKDF2_ITERATIONS}${salt.hex()}${digest.hex()}"


def verify_secret(secret: str, stored: str) -> bool:
    """Constant-time verify ``secret`` against a value produced by :func:`hash_secret`.

    Returns ``False`` (never raises) for a malformed ``stored`` value, so a
    corrupt/unexpected row never turns into a 500 instead of a rejected
    credential.
    """
    parts = stored.split("$")
    if len(parts) != _STORED_SECRET_PART_COUNT or parts[0] != _STORED_SECRET_PREFIX:
        return False
    _, iterations_raw, salt_hex, digest_hex = parts
    try:
        iterations = int(iterations_raw)
        salt = bytes.fromhex(salt_hex)
        expected_digest = bytes.fromhex(digest_hex)
    except ValueError:
        return False
    candidate_digest = _pbkdf2_digest(secret, salt, iterations)
    return hmac.compare_digest(candidate_digest, expected_digest)


def generate_otp_code() -> str:
    """Generate a zero-padded 6-digit OTP code using a CSPRNG."""
    return f"{secrets.randbelow(10**_OTP_DIGITS):0{_OTP_DIGITS}d}"


def generate_public_user_id() -> str:
    """Generate a short human-shareable pairing code for a new user account.

    This is what the person types into the paired Android device's own
    registration screen as its ``user_id`` -- distinct from the internal
    UUID primary key, which is never shown to a person.
    """
    return secrets.token_hex(4).upper()


def generate_session_token() -> str:
    """Generate a high-entropy opaque session token for a logged-in user."""
    return secrets.token_urlsafe(32)


def hash_token(token: str) -> str:
    """Hash a high-entropy opaque token (session token) for storage/lookup."""
    return hashlib.sha256(token.encode("utf-8")).hexdigest()

"""Unit tests for password/OTP/session-token hashing primitives."""

from app.security import (
    generate_otp_code,
    generate_public_user_id,
    generate_session_token,
    hash_secret,
    hash_token,
    verify_secret,
)


def test_hash_secret_verifies_the_same_secret() -> None:
    """Accept the exact secret that produced the stored hash."""
    stored = hash_secret("correct horse battery staple")
    assert verify_secret("correct horse battery staple", stored)


def test_hash_secret_rejects_a_different_secret() -> None:
    """Reject a secret that does not match the stored hash."""
    stored = hash_secret("correct horse battery staple")
    assert not verify_secret("wrong password", stored)


def test_hash_secret_never_stores_the_plaintext() -> None:
    """Never embed the original plaintext secret in the stored hash string."""
    stored = hash_secret("super-secret-otp")
    assert "super-secret-otp" not in stored


def test_hash_secret_uses_a_fresh_salt_each_call() -> None:
    """Produce a different stored hash each call, both still verifiable."""
    first = hash_secret("same-input")
    second = hash_secret("same-input")
    assert first != second
    assert verify_secret("same-input", first)
    assert verify_secret("same-input", second)


def test_verify_secret_rejects_malformed_stored_value() -> None:
    """Return `False`, never raise, for a corrupt or unrecognized stored value."""
    assert not verify_secret("anything", "not-a-valid-stored-hash")
    assert not verify_secret("anything", "pbkdf2_sha256$not-an-int$aa$bb")
    assert not verify_secret("anything", "wrong_algorithm$1$aa$bb")


def test_generate_otp_code_is_six_digits() -> None:
    """Generate a zero-padded 6-digit numeric OTP code."""
    code = generate_otp_code()
    assert len(code) == 6
    assert code.isdigit()


def test_generate_public_user_id_is_unique_across_calls() -> None:
    """Generate distinct pairing codes across many calls."""
    ids = {generate_public_user_id() for _ in range(50)}
    assert len(ids) == 50


def test_generate_session_token_is_unique_across_calls() -> None:
    """Generate distinct session tokens across many calls."""
    tokens = {generate_session_token() for _ in range(50)}
    assert len(tokens) == 50


def test_hash_token_is_deterministic_for_lookup() -> None:
    """Hash the same token to the same digest, enabling lookup by hash."""
    token = generate_session_token()
    assert hash_token(token) == hash_token(token)


def test_hash_token_differs_for_different_tokens() -> None:
    """Hash different tokens to different digests."""
    assert hash_token("token-a") != hash_token("token-b")

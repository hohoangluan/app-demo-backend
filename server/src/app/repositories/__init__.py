"""Repository layer: query, locking, and persistence only.

Repositories never make HTTP-status or Public-error-code decisions and never
commit transactions themselves; the caller (service layer) owns the
transaction boundary.
"""

from __future__ import annotations

import base64
import getpass
import hashlib
import secrets

SCHEME = "pbkdf2_sha256"
ITERATIONS = 600_000


def main() -> None:
    password = getpass.getpass("New AAS password: ")
    confirmation = getpass.getpass("Repeat password: ")
    if not password:
        raise SystemExit("Password must not be empty")
    if password != confirmation:
        raise SystemExit("Passwords do not match")
    salt = secrets.token_bytes(16)
    digest = hashlib.pbkdf2_hmac(
        "sha256", password.encode("utf-8"), salt, ITERATIONS, dklen=32
    )
    salt_text = base64.urlsafe_b64encode(salt).decode("ascii")
    digest_text = base64.urlsafe_b64encode(digest).decode("ascii")
    print(f"{SCHEME}${ITERATIONS}${salt_text}${digest_text}")


if __name__ == "__main__":
    main()

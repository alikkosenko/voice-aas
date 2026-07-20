# AAS password server

## 1. Install

```bash
python3 -m venv .venv
source .venv/bin/activate
pip install -r requirements.txt
```

Windows activation:

```bat
.venv\Scripts\activate
```

## 2. Generate password hash

```bash
python set_password.py
```

Copy the printed value into the `AAS_PASSWORD_HASH` environment variable.
The plaintext password is not stored by the server.

Linux/macOS example:

```bash
export AAS_PASSWORD_HASH='pbkdf2_sha256$600000$...'
uvicorn server:app --host 127.0.0.1 --port 8080
```

PowerShell example:

```powershell
$env:AAS_PASSWORD_HASH='pbkdf2_sha256$600000$...'
uvicorn server:app --host 127.0.0.1 --port 8080
```

## 3. HTTPS

Run Uvicorn behind Nginx/Caddy and publish:

```text
https://your-domain.example/api/check-password
```

AAS intentionally rejects ordinary remote HTTP. Only HTTPS is accepted; HTTP is
allowed solely for localhost development.

## 4. Configure Android app

Edit:

```text
app/src/main/res/values/auth_config.xml
```

Replace `auth_server_url` with the public HTTPS endpoint. The default authorization
lifetime is 720 hours (30 days). Set `auth_validity_hours` to `0` for no expiry.

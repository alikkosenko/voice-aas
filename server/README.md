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

## 3. HTTP through Nginx

Run Uvicorn on `127.0.0.1:8080` and use the included `nginx-aas-password-http.conf`.

Public endpoint:

```text
http://SERVER_IP/api/check-password
```

See `INSTALL_SYSTEMD_HTTP.md` for installation commands.

## 4. Configure Android app

Edit:

```text
app/src/main/res/values/auth_config.xml
```

Enter the public HTTP endpoint on the AAS login screen. The address is saved locally. The default authorization
lifetime is 720 hours (30 days). Set `auth_validity_hours` to `0` for no expiry.

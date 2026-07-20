# Install with systemd and Nginx over HTTP

```bash
sudo mkdir -p /opt/aas-password-server
sudo cp server.py set_password.py requirements.txt /opt/aas-password-server/
sudo useradd --system --home /opt/aas-password-server --shell /usr/sbin/nologin aasauth || true
sudo python3 -m venv /opt/aas-password-server/.venv
sudo /opt/aas-password-server/.venv/bin/pip install -r /opt/aas-password-server/requirements.txt
sudo chown -R aasauth:aasauth /opt/aas-password-server
cd /opt/aas-password-server
sudo -u aasauth .venv/bin/python set_password.py
```

Put the generated hash into `/etc/aas-password-server.env`:

```text
AAS_PASSWORD_HASH='pbkdf2_sha256$600000$...$...'
```

Then:

```bash
sudo chmod 600 /etc/aas-password-server.env
sudo cp aas-password-server.service /etc/systemd/system/
sudo systemctl daemon-reload
sudo systemctl enable --now aas-password-server
sudo cp nginx-aas-password-http.conf /etc/nginx/sites-available/aas-password-server
sudo ln -sf /etc/nginx/sites-available/aas-password-server /etc/nginx/sites-enabled/aas-password-server
sudo nginx -t
sudo systemctl reload nginx
```

Test:

```bash
curl http://SERVER_IP/health
curl -X POST http://SERVER_IP/api/check-password -H 'Content-Type: application/json' -d '{"password":"YOUR_PASSWORD"}'
```

In AAS enter `http://SERVER_IP/api/check-password`.

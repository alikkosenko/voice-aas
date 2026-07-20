# HTTP authorization setup

On the login screen enter:

```text
http://SERVER_IP/api/check-password
```

If Uvicorn is exposed directly on port 8080:

```text
http://SERVER_IP:8080/api/check-password
```

HTTP sends the password without transport encryption. Use only where this risk is acceptable.

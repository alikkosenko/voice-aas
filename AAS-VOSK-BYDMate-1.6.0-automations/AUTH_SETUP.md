# Настройка серверной авторизации AAS 1.3.0

## Сервер

1. Перейдите в каталог `server`.
2. Создайте виртуальное окружение и установите `requirements.txt`.
3. Запустите `python set_password.py` и сохраните выданный PBKDF2-хеш.
4. Перед запуском задайте переменную окружения `AAS_PASSWORD_HASH`.
5. Запустите Uvicorn на локальном порту и опубликуйте его через HTTPS reverse proxy.

Сервер принимает:

```http
POST /api/check-password
Content-Type: application/json

{"password":"введённый пароль"}
```

Ответ:

```json
{"valid":true}
```

или:

```json
{"valid":false}
```

## Android

Перед сборкой измените:

```text
app/src/main/res/values/auth_config.xml
```

Пример:

```xml
<string name="auth_server_url" translatable="false">https://aas.example.com/api/check-password</string>
<integer name="auth_validity_hours">720</integer>
```

Удалённый HTTP намеренно запрещён. Сервер должен иметь действительный HTTPS-сертификат.
Пароль не сохраняется в приложении; хранится только срок действия успешной авторизации.

## Ограничение защиты

Это простая серверная проверка доступа, а не полноценная DRM-система. Человек,
который модифицирует и пересобирает APK, теоретически может удалить проверку.
Для коммерческого лицензирования лучше использовать привязку к VIN/устройству и
подписанные сервером короткоживущие токены.

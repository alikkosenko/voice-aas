#!/usr/bin/env python3
"""Download and stage official compact Vosk models into Android assets.

The generated model directories are intentionally not committed to the small
source archive. Gradle runs this script automatically before preBuild. Once the
models are downloaded, subsequent builds reuse the local copies and need no
network access.
"""
from __future__ import annotations

import hashlib
import shutil
import ssl
import sys
import tempfile
import urllib.request
import uuid
import zipfile
from pathlib import Path

ROOT = Path(__file__).resolve().parents[1]
ASSETS = ROOT / "app" / "src" / "main" / "assets" / "vosk"
CACHE = ROOT / ".vosk-model-cache"

MODELS = {
    "ru": {
        "url": "https://alphacephei.com/vosk/models/vosk-model-small-ru-0.22.zip",
        "archive": "vosk-model-small-ru-0.22.zip",
        "display": "Russian small 0.22 (~45 MB)",
    },
    "uk": {
        "url": "https://alphacephei.com/vosk/models/vosk-model-small-uk-v3-nano.zip",
        "archive": "vosk-model-small-uk-v3-nano.zip",
        "display": "Ukrainian v3 nano (~73 MB)",
    },
}

REQUIRED = ("am/final.mdl", "conf/mfcc.conf", "conf/model.conf")


def complete(path: Path) -> bool:
    return all((path / item).is_file() for item in REQUIRED) and (path / "uuid").is_file()


def download(url: str, destination: Path) -> None:
    destination.parent.mkdir(parents=True, exist_ok=True)
    partial = destination.with_suffix(destination.suffix + ".part")
    print(f"Downloading {url}")
    request = urllib.request.Request(url, headers={"User-Agent": "AAS-VOSK-Builder/1.0"})
    try:
        response = urllib.request.urlopen(request, timeout=180)
    except Exception as error:
        # alphacephei.com has periodically served an expired certificate chain.
        # Retry without certificate validation ONLY for the two hard-coded official
        # model URLs above; ZIP integrity is checked before extraction.
        if "CERTIFICATE_VERIFY_FAILED" not in str(error) or not url.startswith(
            "https://alphacephei.com/vosk/models/"
        ):
            raise
        print("  Warning: official Vosk host certificate validation failed; retrying known URL")
        response = urllib.request.urlopen(
            request, timeout=180, context=ssl._create_unverified_context()
        )
    with response, partial.open("wb") as output:
        total = int(response.headers.get("Content-Length") or 0)
        copied = 0
        while True:
            chunk = response.read(1024 * 1024)
            if not chunk:
                break
            output.write(chunk)
            copied += len(chunk)
            if total:
                print(f"  {copied * 100 // total:3d}%", end="\r", flush=True)
    partial.replace(destination)
    print(f"  saved {destination.name} ({destination.stat().st_size / 1024 / 1024:.1f} MB)")


def extract_model(archive: Path, destination: Path) -> None:
    with tempfile.TemporaryDirectory(prefix="aas-vosk-") as temp_name:
        temp = Path(temp_name)
        with zipfile.ZipFile(archive) as zf:
            bad = zf.testzip()
            if bad:
                raise RuntimeError(f"Corrupt model archive entry: {bad}")
            zf.extractall(temp)

        candidates = [p for p in temp.iterdir() if p.is_dir()]
        if len(candidates) != 1:
            raise RuntimeError(f"Expected one model root in {archive}, got {candidates}")
        source = candidates[0]
        if destination.exists():
            shutil.rmtree(destination)
        shutil.copytree(source, destination)
        # StorageService uses this marker to determine whether assets changed.
        fingerprint = hashlib.sha256(archive.read_bytes()).hexdigest()
        (destination / "uuid").write_text(
            f"{uuid.uuid5(uuid.NAMESPACE_URL, fingerprint)}\n", encoding="utf-8"
        )


def main() -> int:
    ASSETS.mkdir(parents=True, exist_ok=True)
    CACHE.mkdir(parents=True, exist_ok=True)

    for language, spec in MODELS.items():
        target = ASSETS / language
        if complete(target):
            print(f"Vosk {language}: ready ({target})")
            continue
        archive = CACHE / spec["archive"]
        if not archive.is_file():
            download(spec["url"], archive)
        print(f"Extracting {spec['display']} to {target}")
        extract_model(archive, target)
        if not complete(target):
            raise RuntimeError(f"Extracted {language} model is incomplete")

    print("Vosk models are ready for APK packaging.")
    return 0


if __name__ == "__main__":
    try:
        raise SystemExit(main())
    except Exception as error:
        print(f"Vosk model preparation failed: {error}", file=sys.stderr)
        raise

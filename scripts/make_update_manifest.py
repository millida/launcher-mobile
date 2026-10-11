import argparse
import datetime
import hashlib
import json
import os
import re
import sys

ROOT = os.path.join(os.path.dirname(__file__), "..")
GRADLE = os.path.join(ROOT, "app", "build.gradle.kts")
PROPERTIES = os.path.join(ROOT, "gradle.properties")


def digest(path):
    sha = hashlib.sha256()
    with open(path, "rb") as handle:
        for chunk in iter(lambda: handle.read(1 << 20), b""):
            sha.update(chunk)
    return sha.hexdigest()


def gradle_value(text, key):
    found = re.search(r"\b" + key + r"\s*=\s*\"?([^\"\n]+)\"?", text)
    if not found:
        sys.exit("в app/build.gradle.kts нет " + key)
    return found.group(1).strip()


def gradle_property(key):
    with open(PROPERTIES, encoding="utf-8") as handle:
        for line in handle:
            name, _, value = line.partition("=")
            if name.strip() == key:
                return value.strip()
    sys.exit("в gradle.properties нет " + key)


def main():
    parser = argparse.ArgumentParser(description="Собирает latest.json канала обновлений из подписанного APK")
    parser.add_argument("apk")
    parser.add_argument("--base", default="https://launcher-storage.millida.net/mobile")
    parser.add_argument("--notes", default="", help="файл с заметками релиза в markdown")
    parser.add_argument("--out", default="-")
    parser.add_argument("--full", default="", help="APK со встроенным рантаймом для первой установки с сайта")
    parser.add_argument("--version-name", default="")
    parser.add_argument("--version-code", type=int, default=0)
    parser.add_argument("--min-sdk", type=int, default=0)
    args = parser.parse_args()

    with open(GRADLE, encoding="utf-8") as handle:
        gradle = handle.read()
    version = args.version_name or gradle_property("millida.versionName")
    version_code = args.version_code or int(gradle_property("millida.versionCode"))
    min_sdk = args.min_sdk or int(gradle_value(gradle, "minSdk"))
    if not re.fullmatch(r"\d+\.\d+\.\d+", version):
        sys.exit("versionName должен быть вида 1.2.3, а не " + version)
    if version_code <= 0:
        sys.exit("versionCode должен быть положительным")

    notes = ""
    if args.notes:
        with open(args.notes, encoding="utf-8") as handle:
            notes = handle.read().strip()

    base = args.base.rstrip("/")
    name = os.path.basename(args.apk)
    android = {
        "versionCode": version_code,
        "url": base + "/" + name,
        "size": os.path.getsize(args.apk),
        "sha256": digest(args.apk),
        "minSdk": min_sdk,
    }
    if args.full:
        android["full"] = {
            "url": base + "/" + os.path.basename(args.full),
            "size": os.path.getsize(args.full),
            "sha256": digest(args.full),
        }
    manifest = {
        "version": version,
        "notes": notes,
        "releasedAt": datetime.date.today().isoformat(),
        "platforms": {"android": android},
    }

    text = json.dumps(manifest, ensure_ascii=False, indent=2)
    if args.out == "-":
        sys.stdout.write(text + "\n")
    else:
        with open(args.out, "w", encoding="utf-8") as handle:
            handle.write(text + "\n")
        print("записан", args.out, "— APK выложить рядом как", name)


if __name__ == "__main__":
    main()

import argparse
import hashlib
import json
import os
import sys

CATEGORIES = {
    "jre": "jre",
    "bridge": "bridge",
    "renderer": "renderers",
    "tool": "tools",
    "sdl": "sdl",
}


def digest(path):
    sha = hashlib.sha256()
    with open(path, "rb") as handle:
        for chunk in iter(lambda: handle.read(1 << 20), b""):
            sha.update(chunk)
    return sha.hexdigest()


def classify(name):
    lowered = name.lower()
    if lowered.startswith("jre"):
        return "jre"
    if lowered.startswith("bridge"):
        return "bridge"
    if lowered.startswith("renderer"):
        return "renderer"
    return "tool"


def entry(category, name, url, sha, size):
    identifier = name[: -len(".zip")] if name.endswith(".zip") else name
    common = {"id": identifier, "url": url, "sha256": sha, "size": size}
    if category == "jre":
        major = 21
        for part in identifier.replace("-", " ").split():
            if part.isdigit():
                major = int(part)
                break
        common.update({"major": major, "abi": "arm64-v8a", "javaHome": ""})
    if category == "bridge":
        common.update({"classpath": []})
    if category == "renderer":
        common.update(
            {
                "label": identifier,
                "library": "libGL.so",
                "eglLibrary": "",
                "glesMajor": 3,
                "glesMinor": 0,
                "shaders": False,
                "environment": {},
            }
        )
    return common


def main():
    parser = argparse.ArgumentParser(description="Собирает манифест рантайма из папки с архивами")
    parser.add_argument("directory")
    parser.add_argument("--base", required=True, help="адрес раздачи без слэша на конце")
    parser.add_argument("--out", default="-")
    parser.add_argument("--revision", type=int, default=1)
    parser.add_argument(
        "--meta",
        default="",
        help="components.json от make_runtime_components.py: точные поля вместо догадок по имени файла",
    )
    args = parser.parse_args()

    manifest = {"revision": args.revision, "jre": [], "bridge": [], "renderers": [], "tools": [], "sdl": []}

    if args.meta:
        with open(args.meta, encoding="utf-8") as handle:
            described = json.load(handle)
        for item in described:
            path = os.path.join(args.directory, item["file"])
            if not os.path.isfile(path):
                sys.exit("в папке нет компонента " + item["file"])
            category = item["category"]
            url = args.base.rstrip("/") + "/" + item["file"]
            common = entry(category, item["file"], url, digest(path), os.path.getsize(path))
            common.update(item["fields"])
            manifest[CATEGORIES[category]].append(common)
        missing = [name for name in ("jre", "bridge", "renderers") if not manifest[name]]
        if missing:
            sys.exit("в манифесте пусто: " + ", ".join(missing))
        write(manifest, args.out)
        return

    for name in sorted(os.listdir(args.directory)):
        path = os.path.join(args.directory, name)
        if not os.path.isfile(path) or not name.endswith(".zip"):
            continue
        category = classify(name)
        manifest[CATEGORIES[category]].append(
            entry(
                category,
                name,
                args.base.rstrip("/") + "/" + name,
                digest(path),
                os.path.getsize(path),
            )
        )

    write(manifest, args.out)


def write(manifest, out):
    text = json.dumps(manifest, ensure_ascii=False, indent=2)
    if out == "-":
        sys.stdout.write(text + "\n")
    else:
        with open(out, "w", encoding="utf-8", newline="\n") as handle:
            handle.write(text + "\n")
        print("записан", out)


if __name__ == "__main__":
    main()

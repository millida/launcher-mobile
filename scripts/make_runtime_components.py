import argparse
import hashlib
import io
import json
import os
import shutil
import sys
import tarfile
import urllib.request
import zipfile

FIXED_TIME = (1980, 1, 1, 0, 0, 0)
JRE_ARCH_FOLDERS = ("aarch64", "arm64")
WINDOW_LAYER = "libpojavexec.so"
SDL_LIBRARY = "libSDL3.so"
BRIDGE_JAR = "lwjgl-glfw-classes.jar"
RENDERER_VARIABLE = b"POJAV_RENDERER"
OWN_RENDERER_VARIABLE = b"MILLIDA_RENDER"
BRIDGE_RENDERER_READERS = {"org/lwjgl/glfw/GLFW.class", "org/lwjgl/opengl/GL.class", "org/lwjgl/opengl/Display.class"}
ELF_MACHINE_AARCH64 = 183


def fail(message):
    sys.exit("ошибка: " + message)


def sha256_of(path):
    sha = hashlib.sha256()
    with open(path, "rb") as handle:
        for chunk in iter(lambda: handle.read(1 << 20), b""):
            sha.update(chunk)
    return sha.hexdigest()


def require_sha256(path, expected, what):
    actual = sha256_of(path)
    if actual != expected.lower():
        fail("{} не совпал с закреплённой суммой: {} вместо {}".format(what, actual, expected))


def fetch_pinned(name, source, cache):
    os.makedirs(cache, exist_ok=True)
    target = os.path.join(cache, "{}-{}".format(source["sha256"][:16], name))
    if os.path.isfile(target) and sha256_of(target) == source["sha256"]:
        return target
    if not source["url"].startswith("https://"):
        fail("источник {} не по https: {}".format(name, source["url"]))
    partial = target + ".part"
    with urllib.request.urlopen(source["url"], timeout=300) as response, open(partial, "wb") as out:
        shutil.copyfileobj(response, out, 1 << 20)
    actual = sha256_of(partial)
    if actual != source["sha256"]:
        os.remove(partial)
        fail("{} с {} не совпал с закреплённой суммой: {}".format(name, source["url"], actual))
    os.replace(partial, target)
    return target


def safe_join(root, relative):
    if relative.startswith(("/", "\\")) or ":" in relative:
        fail("абсолютный путь в архиве: " + relative)
    target = os.path.realpath(os.path.join(root, relative))
    base = os.path.realpath(root)
    if target != base and not target.startswith(base + os.sep):
        fail("путь выходит за пределы папки: " + relative)
    return target


def unpack_from_pack(pack, prefix, target):
    count = 0
    with zipfile.ZipFile(pack) as archive:
        for info in archive.infolist():
            if not info.filename.startswith(prefix) or info.is_dir():
                continue
            relative = info.filename[len(prefix):]
            if not relative:
                continue
            destination = safe_join(target, relative)
            os.makedirs(os.path.dirname(destination), exist_ok=True)
            with archive.open(info) as source, open(destination, "wb") as out:
                shutil.copyfileobj(source, out, 1 << 20)
            count += 1
    if count == 0:
        fail("в пакете нет папки " + prefix)


def unpack_tar(archive, target):
    links = []
    with tarfile.open(archive, "r:*") as tar:
        for member in tar:
            relative = member.name[2:] if member.name.startswith("./") else member.name
            if not relative or relative == ".":
                continue
            destination = safe_join(target, relative)
            if member.isdir():
                os.makedirs(destination, exist_ok=True)
                continue
            if member.issym():
                pointed = os.path.normpath(os.path.join(os.path.dirname(relative), member.linkname))
                links.append((destination, safe_join(target, pointed.replace("\\", "/"))))
                continue
            if not member.isfile():
                fail("в архиве не обычный файл: " + member.name)
            os.makedirs(os.path.dirname(destination), exist_ok=True)
            with tar.extractfile(member) as source, open(destination, "wb") as out:
                shutil.copyfileobj(source, out, 1 << 20)
    for destination, source in links:
        os.makedirs(os.path.dirname(destination), exist_ok=True)
        if os.path.isfile(source):
            shutil.copyfile(source, destination)
        elif os.path.isdir(source):
            shutil.copytree(source, destination, dirs_exist_ok=True)
        else:
            fail("ссылка в архиве ведёт в никуда: " + os.path.relpath(destination, target))


def java_major(home):
    release = os.path.join(home, "release")
    if not os.path.isfile(release):
        return None
    with open(release, encoding="utf-8") as handle:
        for line in handle:
            if line.startswith("JAVA_VERSION="):
                parts = line.split("=", 1)[1].strip().strip('"').replace("_", ".").split(".")
                first = int(parts[0]) if parts[0].isdigit() else None
                if first == 1 and len(parts) > 1 and parts[1].isdigit():
                    return int(parts[1])
                return first
    return None


def check_jre(home, major):
    if not os.path.isfile(os.path.join(home, "bin", "java")):
        fail("в java {} нет bin/java".format(major))
    candidates = [os.path.join(home, "lib", "server", "libjvm.so")]
    candidates += [os.path.join(home, "lib", arch, "server", "libjvm.so") for arch in JRE_ARCH_FOLDERS]
    if not any(os.path.isfile(path) for path in candidates):
        fail("в java {} нет libjvm.so".format(major))
    declared = java_major(home)
    if declared is not None and declared != major:
        fail("папка java {} на деле содержит java {}".format(major, declared))


def write_notice(folder, component, sources, cache):
    lines = ["Millida mobile runtime component: " + component["id"], ""]
    lines += component.get("notice", [])
    licenses = component.get("licenses", [])
    if licenses:
        os.makedirs(os.path.join(folder, "licenses"), exist_ok=True)
        lines += ["", "License texts: licenses/"]
    for name in licenses:
        path = fetch_pinned(name, sources["downloads"][name], cache)
        shutil.copyfile(path, os.path.join(folder, "licenses", name))
    with open(os.path.join(folder, "NOTICE.txt"), "w", encoding="utf-8", newline="\n") as handle:
        handle.write("\n".join(lines) + "\n")


def deterministic_zip(folder, target):
    entries = []
    for root, _, files in os.walk(folder):
        for name in files:
            full = os.path.join(root, name)
            entries.append((os.path.relpath(full, folder).replace("\\", "/"), full))
    entries.sort()
    with zipfile.ZipFile(target, "w", zipfile.ZIP_DEFLATED, compresslevel=9) as out:
        for relative, full in entries:
            info = zipfile.ZipInfo(relative, date_time=FIXED_TIME)
            executable = relative.endswith(".so") or "/bin/" in "/" + relative
            info.external_attr = (0o100755 if executable else 0o100644) << 16
            info.compress_type = zipfile.ZIP_DEFLATED
            with open(full, "rb") as source:
                out.writestr(info, source.read())


# Sodium refuses to start while POJAV_RENDERER is set, so the window layer and
# the bridge read the renderer from a variable of the same length instead.
def rename_in_window_layer(folder):
    path = os.path.join(folder, WINDOW_LAYER)
    if not os.path.isfile(path):
        fail("в нативной части нет " + WINDOW_LAYER)
    with open(path, "rb") as handle:
        data = handle.read()
    old = RENDERER_VARIABLE + b"\x00"
    count = data.count(old)
    if count != 1:
        fail("{}: строка {} встречается {} раз вместо одного".format(WINDOW_LAYER, RENDERER_VARIABLE.decode(), count))
    start = data.index(old)
    if start == 0 or data[start - 1] != 0:
        fail("{}: {} — хвост другой строки, переименовать нельзя".format(WINDOW_LAYER, RENDERER_VARIABLE.decode()))
    with open(path, "wb") as handle:
        handle.write(data.replace(old, OWN_RENDERER_VARIABLE + b"\x00"))


def rename_in_bridge(folder):
    path = os.path.join(folder, BRIDGE_JAR)
    if not os.path.isfile(path):
        fail("в мосте нет " + BRIDGE_JAR)
    old = b"\x01" + len(RENDERER_VARIABLE).to_bytes(2, "big") + RENDERER_VARIABLE
    new = b"\x01" + len(OWN_RENDERER_VARIABLE).to_bytes(2, "big") + OWN_RENDERER_VARIABLE
    renamed = set()
    staging = path + ".part"
    with zipfile.ZipFile(path) as source, zipfile.ZipFile(staging, "w") as out:
        for info in source.infolist():
            data = source.read(info)
            if RENDERER_VARIABLE in data:
                if data.count(old) != 1 or data.count(RENDERER_VARIABLE) != 1:
                    fail("{}: {} не одной строкой константы".format(info.filename, RENDERER_VARIABLE.decode()))
                data = data.replace(old, new)
                renamed.add(info.filename)
            out.writestr(info, data)
    if renamed != BRIDGE_RENDERER_READERS:
        os.remove(staging)
        fail("в {} {} читают другие классы: {}".format(BRIDGE_JAR, RENDERER_VARIABLE.decode(), sorted(renamed)))
    os.replace(staging, path)


def require_arm64_elf(data, what):
    if len(data) < 20 or data[:4] != b"\x7fELF" or data[4] != 2 or data[5] != 1:
        fail(what + " — не 64-битная библиотека ELF")
    if int.from_bytes(data[18:20], "little") != ELF_MACHINE_AARCH64:
        fail(what + " собрана не под arm64")


def add_downloaded_files(folder, component, sources, cache):
    for item in component.get("addFiles", []):
        archive = fetch_pinned(item["download"], sources["downloads"][item["download"]], cache)
        destination = safe_join(folder, item["as"])
        if os.path.exists(destination):
            fail("{}: {} уже есть в компоненте, подмена запрещена".format(component["id"], item["as"]))
        with zipfile.ZipFile(archive) as source:
            if item["member"] not in source.namelist():
                fail("в {} нет {}".format(item["download"], item["member"]))
            data = source.read(item["member"])
        if item["as"].endswith(".so"):
            require_arm64_elf(data, item["member"])
        with open(destination, "wb") as out:
            out.write(data)


def describe_pack(pack):
    with zipfile.ZipFile(pack) as archive:
        return json.loads(archive.read("millida-runtime.json").decode("utf-8"))


def meta_for(component, description, folder, sources):
    category = component["category"]
    if category == "sdl":
        build = sources["builds"][component["fromBuild"]]
        with open(os.path.join(folder, "millida-sdl.json"), encoding="utf-8") as handle:
            built = json.load(handle)
        if built["sdlVersion"] != build["sdl"]["version"] or built["lwjglVersion"] != build["lwjgl"]["version"]:
            fail("сборка SDL3 не от закреплённых версий: {} / {}".format(built["sdlVersion"], built["lwjglVersion"]))
        for name in build["jars"] + [SDL_LIBRARY]:
            if not os.path.isfile(os.path.join(folder, name)):
                fail("в компоненте SDL3 нет " + name)
        return [{"category": "sdl", "fields": {"id": component["id"], "sdlVersion": built["sdlVersion"],
                                                "lwjglVersion": built["lwjglVersion"], "library": SDL_LIBRARY,
                                                "classpath": build["jars"]}}]
    if category == "jre":
        return [{"category": "jre", "fields": {"id": component["id"], "major": component["major"],
                                                "abi": "arm64-v8a", "javaHome": ""}}]
    if category == "bridge":
        classpath = [entry.split("/", 1)[1] for entry in description["bridge"] if entry.startswith("bridge/")]
        for jar in classpath:
            if not os.path.isfile(os.path.join(folder, jar)):
                fail("в мосте нет " + jar)
        return [{"category": "bridge", "fields": {"id": component["id"], "classpath": classpath,
                                                   "lwjglVersion": description.get("lwjglVersion", "")}}]
    if category == "renderer":
        if not os.path.isfile(os.path.join(folder, WINDOW_LAYER)):
            fail("в нативной части нет " + WINDOW_LAYER)
        out = []
        renderers = description["renderers"] + component.get("addRenderers", [])
        ids = [renderer["id"] for renderer in renderers]
        if len(ids) != len(set(ids)):
            fail("у рендереров компонента {} повторяются id: {}".format(component["id"], ids))
        for renderer in renderers:
            if not os.path.isfile(os.path.join(folder, renderer["library"])):
                fail("рендерер {} ссылается на отсутствующую {}".format(renderer["id"], renderer["library"]))
            out.append({"category": "renderer", "fields": {
                "id": renderer["id"],
                "label": renderer["label"],
                "library": renderer["library"],
                "eglLibrary": renderer.get("eglLibrary", ""),
                "nativeId": renderer.get("nativeId", ""),
                "shaders": renderer.get("shaders", False),
                "environment": renderer.get("environment", {}),
                "component": component["id"],
            }})
        if not out:
            fail("в описании пакета нет ни одного рендерера")
        return out
    fail("неизвестный вид компонента: " + category)


def main():
    parser = argparse.ArgumentParser(description="Раскладывает рантайм на компоненты по закреплённым источникам")
    parser.add_argument("--sources", required=True)
    parser.add_argument("--pack", required=True, help="millida-runtime-arm64.zip, уже скачанный из бакета")
    parser.add_argument("--cache", required=True)
    parser.add_argument("--out", required=True)
    parser.add_argument("--built", action="append", default=[], metavar="ИМЯ=ПАПКА",
                        help="готовая сборка компонента fromBuild, например sdl3=/tmp/sdl3 от build_sdl_runtime.py")
    args = parser.parse_args()
    built = {}
    for item in args.built:
        name, _, path = item.partition("=")
        if not name or not os.path.isdir(path):
            fail("--built ждёт ИМЯ=ПАПКА, а получил " + item)
        built[name] = path

    with open(args.sources, encoding="utf-8") as handle:
        sources = json.load(handle)
    require_sha256(args.pack, sources["pack"]["sha256"], "пакет рантайма")
    description = describe_pack(args.pack)

    work = os.path.join(args.out, "work")
    components = os.path.join(args.out, "components")
    for path in (work, components):
        if os.path.isdir(path):
            shutil.rmtree(path)
        os.makedirs(path)

    meta = []
    for component in sources["components"]:
        folder = os.path.join(work, component["id"])
        os.makedirs(folder)
        if "fromPack" in component:
            unpack_from_pack(args.pack, component["fromPack"], folder)
        elif "fromDownload" in component:
            name = component["fromDownload"]
            unpack_tar(fetch_pinned(name, sources["downloads"][name], args.cache), folder)
        elif "fromBuild" in component:
            name = component["fromBuild"]
            if name not in built:
                fail("компонент {} собирается заранее: передай --built {}=ПАПКА".format(component["id"], name))
            shutil.copytree(built[name], folder, dirs_exist_ok=True)
        else:
            fail("у компонента {} не указан источник".format(component["id"]))
        add_downloaded_files(folder, component, sources, args.cache)
        if component["category"] == "jre":
            check_jre(folder, component["major"])
        if component["category"] == "renderer":
            rename_in_window_layer(folder)
        if component["category"] == "bridge":
            rename_in_bridge(folder)
        write_notice(folder, component, sources, args.cache)

        staging = os.path.join(components, component["id"] + ".zip.part")
        deterministic_zip(folder, staging)
        name = "{}-{}.zip".format(component["id"], sha256_of(staging)[:16])
        os.replace(staging, os.path.join(components, name))
        for entry in meta_for(component, description, folder, sources):
            entry["file"] = name
            meta.append(entry)
        print("компонент", name, round(os.path.getsize(os.path.join(components, name)) / 1048576, 1), "МБ")

    with open(os.path.join(args.out, "components.json"), "w", encoding="utf-8", newline="\n") as handle:
        json.dump(meta, handle, ensure_ascii=False, indent=2)
        handle.write("\n")
    shutil.rmtree(work)


if __name__ == "__main__":
    main()

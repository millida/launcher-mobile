import argparse
import os
import re
import sys

TABLE_CLASSES = {
    "SDLActivity_tab": "SDLActivity",
    "SDLInputConnection_tab": "SDLInputConnection",
    "SDLAudioManager_tab": "SDLAudioManager",
    "SDLControllerManager_tab": "SDLControllerManager",
    "HIDDeviceManager_tab": "HIDDeviceManager",
}

CALLBACK_CLASSES = {
    "mActivityClass": "SDLActivity",
    "mAudioManagerClass": "SDLAudioManager",
    "mControllerManagerClass": "SDLControllerManager",
}

JAVA_TYPES = {
    "void": "V",
    "boolean": "Z",
    "byte": "B",
    "char": "C",
    "short": "S",
    "int": "I",
    "long": "J",
    "float": "F",
    "double": "D",
    "String": "Ljava/lang/String;",
    "Object": "Ljava/lang/Object;",
    "Surface": "Landroid/view/Surface;",
    "Activity": "Landroid/app/Activity;",
}

TABLE = re.compile(r"JNINativeMethod\s+(\w+_tab)\s*\[\s*\d*\s*\]\s*=\s*\{(.*?)\};", re.S)
ENTRY = re.compile(r'\{\s*"(\w+)"\s*,\s*"([^"]+)"')
CALLBACK = re.compile(r'GetStaticMethodID\(\s*env\s*,\s*(\w+)\s*,\s*"(\w+)"\s*,\s*"([^"]+)"\s*\)', re.S)
METHOD = re.compile(
    r"^\s*((?:public|private|protected|static|native|final|synchronized|\s)+)\s+([\w.\[\]]+)\s+(\w+)\s*\(([^)]*)\)\s*[;{]",
    re.M,
)


def fail(message):
    sys.exit("ошибка: " + message)


def read(path):
    with open(path, encoding="utf-8") as handle:
        return handle.read()


def required(sdl_root):
    natives = {}
    callbacks = {}
    sources = [
        os.path.join(sdl_root, "src", "core", "android", "SDL_android.c"),
        os.path.join(sdl_root, "src", "hidapi", "android", "hid.cpp"),
    ]
    for path in sources:
        text = read(path)
        for table, body in TABLE.findall(text):
            owner = TABLE_CLASSES.get(table)
            if owner is None:
                fail("незнакомая таблица JNI {} в {}".format(table, path))
            for name, signature in ENTRY.findall(body):
                natives.setdefault(owner, {})[name] = signature
        for holder, name, signature in CALLBACK.findall(text):
            owner = CALLBACK_CLASSES.get(holder)
            if owner is not None:
                callbacks.setdefault(owner, {})[name] = " ".join(signature.split())
    for owner in TABLE_CLASSES.values():
        if owner not in natives:
            fail("в исходниках SDL нет таблицы для " + owner)
    return natives, callbacks


def java_type(raw):
    raw = raw.strip()
    dims = raw.count("[]")
    base = raw.replace("[]", "").split(".")[-1]
    if base not in JAVA_TYPES:
        fail("тип {} не умею переводить в подпись JNI".format(raw))
    return "[" * dims + JAVA_TYPES[base]


def signature(params, result):
    args = []
    for param in [p for p in params.split(",") if p.strip()]:
        words = param.replace("final ", "").split()
        args.append(java_type(" ".join(words[:-1])))
    return "(" + "".join(args) + ")" + java_type(result)


def declared(glue_dir, owner):
    path = os.path.join(glue_dir, owner + ".java")
    if not os.path.isfile(path):
        fail("нет класса " + path)
    out = {}
    for modifiers, result, name, params in METHOD.findall(read(path)):
        words = modifiers.split()
        out[name] = {
            "params": params,
            "result": result,
            "native": "native" in words,
            "static": "static" in words,
        }
    return out


def main():
    parser = argparse.ArgumentParser(description="Сверяет наш слой org.libsdl.app с таблицами JNI закреплённого SDL")
    parser.add_argument("--sdl", required=True, help="корень исходников SDL того же тега, что собирается")
    parser.add_argument("--glue", required=True, help="папка app/src/main/java/org/libsdl/app")
    args = parser.parse_args()

    natives, callbacks = required(args.sdl)
    problems = []
    for owner, methods in sorted(natives.items()):
        ours = declared(args.glue, owner)
        for name, wanted in sorted(methods.items()):
            got = ours.get(name)
            if got is None or not got["native"]:
                problems.append("{}.{}{}: нет native-метода — RegisterNatives откажет всему классу".format(owner, name, wanted))
            elif signature(got["params"], got["result"]) != wanted:
                problems.append("{}.{}: подпись {} вместо {}".format(owner, name, signature(got["params"], got["result"]), wanted))
    for owner, methods in sorted(callbacks.items()):
        ours = declared(args.glue, owner)
        for name, wanted in sorted(methods.items()):
            got = ours.get(name)
            if got is None or got["native"] or not got["static"]:
                problems.append("{}.{}{}: нет статического метода — SDL позовёт пустой идентификатор".format(owner, name, wanted))
            elif signature(got["params"], got["result"]) != wanted:
                problems.append("{}.{}: подпись {} вместо {}".format(owner, name, signature(got["params"], got["result"]), wanted))
    if problems:
        fail("слой org.libsdl.app не совпал с SDL:\n  " + "\n  ".join(problems))
    count = sum(len(m) for m in natives.values()) + sum(len(m) for m in callbacks.values())
    print("слой org.libsdl.app совпал с SDL: {} методов".format(count))


if __name__ == "__main__":
    main()

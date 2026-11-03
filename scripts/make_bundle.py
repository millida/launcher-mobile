import argparse
import json
import os
import shutil
import sys
import tempfile
import zipfile

from make_runtime_components import ABIS, DEFAULT_ABI, elf_problem, require_abi_libraries, unpack_tar

SRC = os.environ.get('MILLIDA_RUNTIME_SRC', os.path.join(tempfile.gettempdir(), 'mruntime'))
WORK = os.path.join(SRC, 'bundle')
APK = os.path.join(SRC, 'zl2.apk')
PROJECT = os.path.dirname(os.path.dirname(os.path.abspath(__file__)))
GLFW_JAR = os.path.join(PROJECT, 'bridge', 'build', 'libs')

JRES = [(21, 'jre-21'), (25, 'jre-25')]

NATIVES = [
    'libgl4es_114.so',
    'libglapi.so',
    'libglxshim.so',
    'liblwjgl.so',
    'liblwjgl_opengl.so',
    'liblwjgl_stb.so',
    'liblwjgl_tinyfd.so',
    'liblwjgl_nanovg.so',
    'liblwjgl_vma.so',
    'libshaderc.so',
    'libspirv-cross-c-shared.so',
    'libopenal.so',
    'libfreetype.so',
    'libc++_shared.so',
    'libEGL_angle.so',
    'libGLESv2_angle.so',
    'libng_gl4es.so',
    'libpojavexec.so',
    'libpojavexec_awt.so',
    'libjnidispatch.so',
    'libexithook.so',
    'libbytehook.so',
    'liblinkerhook.so',
    'libdriver_helper.so',
    'libcutils.so',
    'libawt_xawt.so',
    'libawt_headless.so',
    'libEGL_mesa.so',
    'libzink_dri.so',
    'libshaderconv.so',
    'libspirv-cross-c-shared.so',
    'libvulkan_check.so',
]

REQUIRED_NATIVES = (
    'libpojavexec.so',
    'libng_gl4es.so',
    'libgl4es_114.so',
    'libopenal.so',
    'libfreetype.so',
    'libjnidispatch.so',
)

# ZalithLauncher2 packs the arm64 build of this AWT stub into the Java 25 of
# every ABI and on the phone overwrites it with its own lib/<abi> copy.
AWT_STUB = 'libawt_xawt.so'

DESCRIPTION = {
    "jres": [{"major": major, "path": folder} for major, folder in JRES],
    "javaHome": JRES[-1][1],
    "bridge": ["bridge/millida-glfw.jar", "bridge/lwjgl-glfw-classes.jar"],
    "rendererId": "krypton",
    "rendererLabel": "Krypton (GLES 3)",
    "rendererDir": "native",
    "rendererLibrary": "libng_gl4es.so",
    "lwjglVersion": "3.3.6",
    "eglLibrary": "",
    "shaders": False,
    "renderers": [
        {
            "id": "krypton",
            "label": "Krypton (GLES 3)",
            "library": "libng_gl4es.so",
            "eglLibrary": "",
            "environment": {
                "LIBGL_ES": "3",
                "LIBGL_GL": "33",
                "LIBGL_AVOID16BITS": "1",
                "LIBGL_NOVAOCACHE": "1",
                "LIBGL_NODOWNSAMPLING": "1",
                "LIBGL_MIPMAP": "3",
                "LIBGL_NOERROR": "1",
                "LIBGL_NOINTOVLHACK": "1",
                "LIBGL_NORMALIZE": "1",
                "LIBGL_SHRINK": "0",
                "LIBGL_VSYNC": "1",
            },
        },
        {
            "id": "krypton-vbo",
            "label": "Krypton без буферов",
            "library": "libng_gl4es.so",
            "eglLibrary": "",
            "environment": {
                "LIBGL_ES": "3",
                "LIBGL_GL": "33",
                "LIBGL_USEVBO": "0",
                "LIBGL_NODEPTHTEX": "1",
                "LIBGL_AVOID16BITS": "1",
                "LIBGL_NOVAOCACHE": "1",
                "LIBGL_NODOWNSAMPLING": "1",
                "LIBGL_MIPMAP": "3",
                "LIBGL_NOERROR": "1",
                "LIBGL_NOINTOVLHACK": "1",
                "LIBGL_NORMALIZE": "1",
                "LIBGL_SHRINK": "0",
                "LIBGL_VSYNC": "1",
            },
        },
        {
            "id": "gl4es",
            "label": "GL4ES 1.1.4, версии до 1.17",
            "library": "libgl4es_114.so",
            "eglLibrary": "",
            "environment": {
                "LIBGL_ES": "2",
                "LIBGL_GL": "21",
                "LIBGL_AVOID16BITS": "1",
                "LIBGL_NOVAOCACHE": "1",
                "LIBGL_NODOWNSAMPLING": "1",
                "LIBGL_MIPMAP": "3",
                "LIBGL_NOERROR": "1",
                "LIBGL_NOINTOVLHACK": "1",
                "LIBGL_NORMALIZE": "1",
                "LIBGL_SHRINK": "0",
                "LIBGL_VSYNC": "1",
            },
        },
    ],
    "environment": {
        "LIBGL_ES": "3",
        "LIBGL_GL": "21",
        "LIBGL_MIPMAP": "3",
        "LIBGL_NORMALIZE": "1",
        "LIBGL_NOINTOVLHACK": "1",
        "LIBGL_NOERROR": "1",
        "LIBGL_SHRINK": "0",
        "LIBGL_VSYNC": "1",
    },
}


def reset(path):
    if os.path.isdir(path):
        shutil.rmtree(path)
    os.makedirs(path, exist_ok=True)


def extract_tar_xz(archive, member, target):
    os.makedirs(target, exist_ok=True)
    temporary = os.path.join(WORK, 'tmp.tar.xz')
    with archive.open(member) as source, open(temporary, 'wb') as handle:
        shutil.copyfileobj(source, handle)
    # tarfile.extractall makes real symlinks where Windows allows it; their
    # targets keep forward slashes and cannot be opened when the pack is zipped.
    unpack_tar(temporary, target)
    os.remove(temporary)


def is_required(name):
    return name in REQUIRED_NATIVES or name.startswith('liblwjgl')


def is_foreign(path, abi):
    with open(path, 'rb') as handle:
        return bool(elf_problem(handle.read(20), abi))


def built_glfw_jar():
    jar = None
    for candidate in os.listdir(GLFW_JAR):
        if candidate.startswith('millida-glfw') and candidate.endswith('.jar'):
            jar = candidate
    if jar is None:
        sys.exit('millida-glfw.jar не собран: запусти gradle :bridge:jar или передай --glfw-jar')
    return os.path.join(GLFW_JAR, jar)


def main():
    parser = argparse.ArgumentParser(description='Собирает пакет рантайма одним файлом из APK ZalithLauncher2')
    parser.add_argument('--abi', choices=sorted(ABIS), default=DEFAULT_ABI)
    parser.add_argument('--apk', default=APK, help='APK ZalithLauncher2 с библиотеками под --abi; по умолчанию zl2.apk в MILLIDA_RUNTIME_SRC')
    parser.add_argument('--out', default='', help='куда писать пакет; по умолчанию dist/millida-runtime-<arm64|arm>.zip')
    parser.add_argument('--glfw-jar', default='', help='готовый millida-glfw.jar; по умолчанию из bridge/build/libs')
    args = parser.parse_args()
    arch = ABIS[args.abi]['arch']
    pack = 'millida-runtime-' + arch
    out_path = args.out or os.path.join(PROJECT, 'dist', pack + '.zip')
    if not os.path.isfile(args.apk):
        sys.exit('нет исходной сборки рантайма: ' + args.apk)
    if args.glfw_jar and not os.path.isfile(args.glfw_jar):
        sys.exit('нет millida-glfw.jar: ' + args.glfw_jar)
    glfw_jar = args.glfw_jar or built_glfw_jar()

    reset(WORK)
    bridge = os.path.join(WORK, 'bridge')
    native = os.path.join(WORK, 'native')
    for path in (bridge, native):
        os.makedirs(path, exist_ok=True)

    with zipfile.ZipFile(args.apk) as archive:
        names = set(archive.namelist())
        libraries = 'lib/{}/'.format(args.abi)
        absent = sorted({name for name in NATIVES if libraries + name not in names})
        required = [name for name in absent if is_required(name)]
        if required:
            sys.exit('в сборке под {} нет обязательных библиотек: {}'.format(args.abi, ', '.join(required)))

        replaced = []
        for major, folder in JRES:
            target = os.path.join(WORK, folder)
            os.makedirs(target, exist_ok=True)
            for part in ('universal.tar.xz', 'bin-{}.tar.xz'.format(arch)):
                member = 'assets/runtimes/{}/{}'.format(folder, part)
                if member not in names:
                    sys.exit('в сборке нет ' + member)
                extract_tar_xz(archive, member, target)
            marker = os.path.join(target, 'lib', 'server', 'libjvm.so')
            if not os.path.isfile(marker):
                sys.exit('java {} распакована без libjvm.so'.format(major))
            stub = os.path.join(target, 'lib', AWT_STUB)
            if os.path.isfile(stub) and is_foreign(stub, args.abi):
                if libraries + AWT_STUB not in names:
                    sys.exit('в java {} {} чужого ABI, а в сборке нет {}'.format(major, AWT_STUB, libraries + AWT_STUB))
                with archive.open(libraries + AWT_STUB) as source, open(stub, 'wb') as handle:
                    shutil.copyfileobj(source, handle)
                replaced.append('{}/lib/{}'.format(folder, AWT_STUB))
            print('java', major, 'готова')

        with archive.open('assets/components/lwjgl3/lwjgl-glfw-classes.jar') as source:
            with open(os.path.join(bridge, 'lwjgl-glfw-classes.jar'), 'wb') as target:
                shutil.copyfileobj(source, target)

        for name in NATIVES:
            if name in absent:
                continue
            with archive.open(libraries + name) as source:
                with open(os.path.join(native, name), 'wb') as target:
                    shutil.copyfileobj(source, target)

    shutil.copy(glfw_jar, os.path.join(bridge, 'millida-glfw.jar'))
    require_abi_libraries(WORK, args.abi)

    with open(os.path.join(WORK, 'millida-runtime.json'), 'w', encoding='utf-8') as handle:
        json.dump({"name": pack, **DESCRIPTION}, handle, ensure_ascii=False, indent=2)

    os.makedirs(os.path.dirname(os.path.abspath(out_path)), exist_ok=True)
    if os.path.exists(out_path):
        os.remove(out_path)
    total = 0
    with zipfile.ZipFile(out_path, 'w', zipfile.ZIP_DEFLATED, compresslevel=6) as out:
        for root, _, files in os.walk(WORK):
            for name in files:
                full = os.path.join(root, name)
                relative = os.path.relpath(full, WORK).replace('\\', '/')
                out.write(full, relative)
                total += 1
    print('файлов в пакете:', total)
    print('размер пакета, МБ:', round(os.path.getsize(out_path) / 1024 / 1024, 1))
    if absent:
        print('в сборке под {} нет необязательных библиотек, в пакет не вошли: {}'.format(args.abi, ', '.join(absent)))
    if replaced:
        print('библиотеки чужого ABI заменены копией из {}: {}'.format(libraries, ', '.join(replaced)))
    print('пакет:', out_path)


if __name__ == '__main__':
    main()

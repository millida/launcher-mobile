import json
import lzma
import os
import shutil
import sys
import tarfile
import tempfile
import zipfile

SRC = os.environ.get('MILLIDA_RUNTIME_SRC', os.path.join(tempfile.gettempdir(), 'mruntime'))
WORK = os.path.join(SRC, 'bundle')
APK = os.path.join(SRC, 'zl2.apk')
PROJECT = os.path.dirname(os.path.dirname(os.path.abspath(__file__)))
GLFW_JAR = os.path.join(PROJECT, 'bridge', 'build', 'libs')
OUT = os.path.join(PROJECT, 'dist', 'millida-runtime-arm64.zip')

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

DESCRIPTION = {
    "name": "millida-runtime-arm64",
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
    data = lzma.decompress(archive.read(member))
    temporary = os.path.join(WORK, 'tmp.tar')
    with open(temporary, 'wb') as handle:
        handle.write(data)
    with tarfile.open(temporary) as tar:
        tar.extractall(target)
    os.remove(temporary)


def main():
    if not os.path.isfile(APK):
        sys.exit('нет исходной сборки рантайма: ' + APK)

    reset(WORK)
    bridge = os.path.join(WORK, 'bridge')
    native = os.path.join(WORK, 'native')
    for path in (bridge, native):
        os.makedirs(path, exist_ok=True)

    with zipfile.ZipFile(APK) as archive:
        names = set(archive.namelist())
        for major, folder in JRES:
            target = os.path.join(WORK, folder)
            os.makedirs(target, exist_ok=True)
            for part in ('universal.tar.xz', 'bin-arm64.tar.xz'):
                member = 'assets/runtimes/{}/{}'.format(folder, part)
                if member not in names:
                    sys.exit('в сборке нет ' + member)
                extract_tar_xz(archive, member, target)
            marker = os.path.join(target, 'lib', 'server', 'libjvm.so')
            if not os.path.isfile(marker):
                sys.exit('java {} распакована без libjvm.so'.format(major))
            print('java', major, 'готова')

        with archive.open('assets/components/lwjgl3/lwjgl-glfw-classes.jar') as source:
            with open(os.path.join(bridge, 'lwjgl-glfw-classes.jar'), 'wb') as target:
                shutil.copyfileobj(source, target)

        for name in NATIVES:
            member = 'lib/arm64-v8a/' + name
            if member not in names:
                print('нет', name)
                continue
            with archive.open(member) as source:
                with open(os.path.join(native, name), 'wb') as target:
                    shutil.copyfileobj(source, target)

    jar = None
    for candidate in os.listdir(GLFW_JAR):
        if candidate.startswith('millida-glfw') and candidate.endswith('.jar'):
            jar = candidate
    if jar is None:
        sys.exit('millida-glfw.jar не собран: запусти gradle :bridge:jar')
    shutil.copy(os.path.join(GLFW_JAR, jar), os.path.join(bridge, 'millida-glfw.jar'))

    with open(os.path.join(WORK, 'millida-runtime.json'), 'w', encoding='utf-8') as handle:
        json.dump(DESCRIPTION, handle, ensure_ascii=False, indent=2)

    os.makedirs(os.path.dirname(OUT), exist_ok=True)
    if os.path.exists(OUT):
        os.remove(OUT)
    total = 0
    with zipfile.ZipFile(OUT, 'w', zipfile.ZIP_DEFLATED, compresslevel=6) as out:
        for root, _, files in os.walk(WORK):
            for name in files:
                full = os.path.join(root, name)
                relative = os.path.relpath(full, WORK).replace('\\', '/')
                out.write(full, relative)
                total += 1
    print('файлов в пакете:', total)
    print('размер пакета, МБ:', round(os.path.getsize(OUT) / 1024 / 1024, 1))
    print('пакет:', OUT)


if __name__ == '__main__':
    main()

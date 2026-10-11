import os

ROOT = os.path.join(os.path.dirname(os.path.dirname(os.path.abspath(__file__))),
                    "bridge", "src", "main", "java", "org", "lwjgl", "glfw")

CALLBACKS = [
    ("GLFWKeyCallback", "void", "invoke", "long window, int key, int scancode, int action, int mods"),
    ("GLFWCharCallback", "void", "invoke", "long window, int codepoint"),
    ("GLFWCharModsCallback", "void", "invoke", "long window, int codepoint, int mods"),
    ("GLFWCursorPosCallback", "void", "invoke", "long window, double xpos, double ypos"),
    ("GLFWCursorEnterCallback", "void", "invoke", "long window, boolean entered"),
    ("GLFWMouseButtonCallback", "void", "invoke", "long window, int button, int action, int mods"),
    ("GLFWScrollCallback", "void", "invoke", "long window, double xoffset, double yoffset"),
    ("GLFWWindowSizeCallback", "void", "invoke", "long window, int width, int height"),
    ("GLFWWindowPosCallback", "void", "invoke", "long window, int xpos, int ypos"),
    ("GLFWWindowFocusCallback", "void", "invoke", "long window, boolean focused"),
    ("GLFWWindowIconifyCallback", "void", "invoke", "long window, boolean iconified"),
    ("GLFWWindowMaximizeCallback", "void", "invoke", "long window, boolean maximized"),
    ("GLFWWindowRefreshCallback", "void", "invoke", "long window"),
    ("GLFWWindowCloseCallback", "void", "invoke", "long window"),
    ("GLFWWindowContentScaleCallback", "void", "invoke", "long window, float xscale, float yscale"),
    ("GLFWFramebufferSizeCallback", "void", "invoke", "long window, int width, int height"),
    ("GLFWDropCallback", "void", "invoke", "long window, int count, long names"),
    ("GLFWJoystickCallback", "void", "invoke", "int jid, int event"),
    ("GLFWMonitorCallback", "void", "invoke", "long monitor, int event"),
]

INTERFACE = """package org.lwjgl.glfw;

@FunctionalInterface
public interface {name}I {{
    {ret} {method}({params});
}}
"""

CLASS = """package org.lwjgl.glfw;

public abstract class {name} implements {name}I {{

    protected {name}() {{
    }}

    public static {name} create({name}I delegate) {{
        if (delegate instanceof {name}) {{
            return ({name}) delegate;
        }}
        return new {name}() {{
            @Override
            public {ret} {method}({params}) {{
                delegate.{method}({args});
            }}
        }};
    }}

    public static {name} createSafe(long handle) {{
        return null;
    }}

    public {name} set(long window) {{
        GLFW.registerCallback(window, this);
        return this;
    }}

    public void free() {{
    }}

    public long address() {{
        return 0L;
    }}
}}
"""


def arg_names(params):
    names = []
    for part in params.split(","):
        part = part.strip()
        if not part:
            continue
        names.append(part.split(" ")[-1])
    return ", ".join(names)


def main():
    os.makedirs(ROOT, exist_ok=True)
    for name, ret, method, params in CALLBACKS:
        with open(os.path.join(ROOT, name + "I.java"), "w", encoding="utf-8") as handle:
            handle.write(INTERFACE.format(name=name, ret=ret, method=method, params=params))
        with open(os.path.join(ROOT, name + ".java"), "w", encoding="utf-8") as handle:
            handle.write(
                CLASS.format(
                    name=name,
                    ret=ret,
                    method=method,
                    params=params,
                    args=arg_names(params),
                )
            )
    print("callbacks written:", len(CALLBACKS) * 2)


if __name__ == "__main__":
    main()

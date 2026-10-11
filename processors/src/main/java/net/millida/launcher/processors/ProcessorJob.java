package net.millida.launcher.processors;

import java.io.File;
import java.io.FileInputStream;
import java.io.IOException;
import java.io.InputStream;
import java.lang.reflect.InvocationTargetException;
import java.lang.reflect.Method;
import java.net.URL;
import java.net.URLClassLoader;
import java.security.MessageDigest;
import java.security.NoSuchAlgorithmException;
import java.util.Enumeration;
import java.util.List;
import java.util.Map;
import java.util.zip.ZipEntry;
import java.util.zip.ZipFile;

/** One patch step: a program from the installer's libraries with its arguments. */
final class ProcessorJob {

    private final File jar;
    private final String mainClass;
    private final List<File> classpath;
    private final List<String> args;
    private final Map<String, String> outputs;

    ProcessorJob(File jar, String mainClass, List<File> classpath, List<String> args, Map<String, String> outputs) {
        this.jar = jar;
        this.mainClass = mainClass;
        this.classpath = classpath;
        this.args = args;
        this.outputs = outputs;
    }

    String name() {
        return jar.getName();
    }

    /** A step whose declared outputs are already on disk with the right hashes is not run again. */
    boolean outputsReady() throws IOException {
        if (outputs.isEmpty()) {
            return false;
        }
        for (Map.Entry<String, String> output : outputs.entrySet()) {
            File file = new File(output.getKey());
            String expected = output.getValue();
            if (!file.isFile()) {
                return false;
            }
            if (isSha1(expected) && !expected.equalsIgnoreCase(sha1(file))) {
                return false;
            }
        }
        return true;
    }

    /**
     * Inputs are checked by hash before the step runs, but an output archive is
     * compressed by the system zlib, and some Android releases produce different
     * deflate bytes from the same content. A readable archive with another hash
     * is therefore accepted and only reported in [notes].
     */
    String brokenOutput(List<String> notes) throws IOException {
        for (Map.Entry<String, String> output : outputs.entrySet()) {
            File file = new File(output.getKey());
            String expected = output.getValue();
            if (!file.isFile()) {
                return file.getName();
            }
            if (!isSha1(expected)) {
                continue;
            }
            String actual = sha1(file);
            if (expected.equalsIgnoreCase(actual)) {
                continue;
            }
            if (!readableArchive(file)) {
                return file.getName();
            }
            notes.add(file.getName() + " sha1 " + actual + " instead of " + expected.toLowerCase() + ", archive is readable");
        }
        return null;
    }

    static boolean readableArchive(File file) {
        if (!file.isFile() || file.length() == 0) {
            return false;
        }
        ZipFile zip;
        try {
            zip = new ZipFile(file);
        } catch (IOException | RuntimeException unreadable) {
            return false;
        }
        try {
            Enumeration<? extends ZipEntry> entries = zip.entries();
            boolean any = false;
            byte[] buffer = new byte[1 << 16];
            while (entries.hasMoreElements()) {
                ZipEntry entry = entries.nextElement();
                any = true;
                if (entry.isDirectory()) {
                    continue;
                }
                InputStream in = zip.getInputStream(entry);
                try {
                    while (in.read(buffer) >= 0) {
                        continue;
                    }
                } finally {
                    in.close();
                }
            }
            return any;
        } catch (IOException | RuntimeException unreadable) {
            return false;
        } finally {
            try {
                zip.close();
            } catch (IOException ignored) {
            }
        }
    }

    /**
     * The step gets a class loader of its own on top of the platform loader, so
     * its libraries never mix with the driver's and the next step starts clean.
     */
    void run() throws Throwable {
        URL[] urls = new URL[classpath.size() + 1];
        urls[0] = jar.toURI().toURL();
        for (int index = 0; index < classpath.size(); index++) {
            urls[index + 1] = classpath.get(index).toURI().toURL();
        }
        Thread thread = Thread.currentThread();
        ClassLoader previous = thread.getContextClassLoader();
        URLClassLoader loader = new URLClassLoader(urls, platformLoader());
        try {
            thread.setContextClassLoader(loader);
            Class<?> entry = Class.forName(mainClass, true, loader);
            Method main = entry.getMethod("main", String[].class);
            main.invoke(null, (Object) args.toArray(new String[0]));
        } catch (InvocationTargetException failed) {
            throw failed.getCause() == null ? failed : failed.getCause();
        } finally {
            thread.setContextClassLoader(previous);
            loader.close();
        }
    }

    private static ClassLoader platformLoader() {
        try {
            Method platform = ClassLoader.class.getMethod("getPlatformClassLoader");
            return (ClassLoader) platform.invoke(null);
        } catch (ReflectiveOperationException java8) {
            return null;
        }
    }

    private static boolean isSha1(String value) {
        if (value == null || value.length() != 40) {
            return false;
        }
        for (int index = 0; index < value.length(); index++) {
            if (Character.digit(value.charAt(index), 16) < 0) {
                return false;
            }
        }
        return true;
    }

    private static String sha1(File file) throws IOException {
        MessageDigest digest;
        try {
            digest = MessageDigest.getInstance("SHA-1");
        } catch (NoSuchAlgorithmException missing) {
            throw new IOException(missing);
        }
        InputStream in = new FileInputStream(file);
        try {
            byte[] buffer = new byte[1 << 16];
            int read;
            while ((read = in.read(buffer)) > 0) {
                digest.update(buffer, 0, read);
            }
        } finally {
            in.close();
        }
        StringBuilder out = new StringBuilder(40);
        for (byte b : digest.digest()) {
            out.append(Character.forDigit((b >> 4) & 0xf, 16)).append(Character.forDigit(b & 0xf, 16));
        }
        return out.toString();
    }
}

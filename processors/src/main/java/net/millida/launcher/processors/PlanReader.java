package net.millida.launcher.processors;

import java.io.BufferedInputStream;
import java.io.DataInputStream;
import java.io.File;
import java.io.FileInputStream;
import java.io.IOException;
import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;

/** Reads the plan written by the launcher's ForgePlanFile. */
final class PlanReader {

    static final int FORMAT = 1;

    private PlanReader() {
    }

    static List<ProcessorJob> read(File plan) throws IOException {
        DataInputStream in = new DataInputStream(new BufferedInputStream(new FileInputStream(plan)));
        try {
            int format = in.readInt();
            if (format != FORMAT) {
                throw new IOException("plan format " + format + ", driver reads " + FORMAT);
            }
            int count = in.readInt();
            List<ProcessorJob> jobs = new ArrayList<ProcessorJob>(count);
            for (int index = 0; index < count; index++) {
                File jar = new File(in.readUTF());
                String mainClass = in.readUTF();
                List<File> classpath = new ArrayList<File>();
                for (String path : strings(in)) {
                    classpath.add(new File(path));
                }
                List<String> args = strings(in);
                int outputCount = in.readInt();
                Map<String, String> outputs = new LinkedHashMap<String, String>();
                for (int output = 0; output < outputCount; output++) {
                    outputs.put(in.readUTF(), in.readUTF());
                }
                jobs.add(new ProcessorJob(jar, mainClass, classpath, args, outputs));
            }
            return jobs;
        } finally {
            in.close();
        }
    }

    private static List<String> strings(DataInputStream in) throws IOException {
        int count = in.readInt();
        List<String> out = new ArrayList<String>(count);
        for (int index = 0; index < count; index++) {
            out.add(in.readUTF());
        }
        return out;
    }
}

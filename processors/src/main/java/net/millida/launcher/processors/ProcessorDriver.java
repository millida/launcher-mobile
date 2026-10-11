package net.millida.launcher.processors;

import java.io.File;
import java.io.FileOutputStream;
import java.io.IOException;
import java.nio.charset.StandardCharsets;
import java.util.ArrayList;
import java.util.List;

/**
 * Runs every patch step of a Forge or NeoForge install inside one Java machine,
 * the way the loader's own installer does on a computer. The launcher reads the
 * status file line by line: the process may end at any point, and whatever was
 * written before that is the whole story.
 */
public final class ProcessorDriver {

    private ProcessorDriver() {
    }

    public static void main(String[] args) {
        if (args.length != 2) {
            System.err.println("usage: ProcessorDriver <plan> <status>");
            return;
        }
        File status = new File(args[1]);
        try {
            List<ProcessorJob> jobs = PlanReader.read(new File(args[0]));
            int total = jobs.size();
            for (int index = 0; index < total; index++) {
                ProcessorJob job = jobs.get(index);
                int step = index + 1;
                if (job.outputsReady()) {
                    report(status, "skip " + step + " " + total);
                    continue;
                }
                report(status, "start " + step + " " + total + " " + job.name());
                job.run();
                List<String> notes = new ArrayList<String>();
                String broken = job.brokenOutput(notes);
                if (broken != null) {
                    throw new IOException("step " + step + " produced a wrong " + broken);
                }
                for (String note : notes) {
                    System.out.println("step " + step + ": " + note);
                    report(status, "note " + step + " " + note);
                }
                report(status, "done " + step + " " + total);
            }
            report(status, "ok");
        } catch (Throwable error) {
            error.printStackTrace();
            try {
                report(status, "fail " + describe(error));
            } catch (IOException unwritable) {
                unwritable.printStackTrace();
            }
        }
    }

    static void report(File status, String line) throws IOException {
        FileOutputStream out = new FileOutputStream(status, true);
        try {
            out.write((line + "\n").getBytes(StandardCharsets.UTF_8));
            out.flush();
            out.getFD().sync();
        } finally {
            out.close();
        }
    }

    static String describe(Throwable error) {
        Throwable root = error;
        while (root.getCause() != null && root.getCause() != root) {
            root = root.getCause();
        }
        String message = root.getMessage();
        String text = root.getClass().getSimpleName() + (message == null ? "" : ": " + message);
        return text.replace('\n', ' ').replace('\r', ' ');
    }
}

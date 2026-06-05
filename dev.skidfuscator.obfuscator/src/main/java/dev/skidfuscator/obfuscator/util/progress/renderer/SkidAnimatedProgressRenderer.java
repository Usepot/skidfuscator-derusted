package dev.skidfuscator.obfuscator.util.progress.renderer;

import dev.skidfuscator.obfuscator.Skidfuscator;
import lukfor.progress.renderer.AnimatedProgressRenderer;
import lukfor.progress.renderer.IProgressIndicator;
import lukfor.progress.tasks.monitors.TaskMonitor;
import org.apache.log4j.Appender;
import org.apache.log4j.AppenderSkeleton;
import org.apache.log4j.LogManager;
import org.apache.log4j.spi.LoggingEvent;
import org.fusesource.jansi.Ansi;

import static org.fusesource.jansi.Ansi.ansi;

public class SkidAnimatedProgressRenderer extends AnimatedProgressRenderer {

    private int lines = 0;

    public static final String ANSI_CSI = (char) 27 + "[";
    private final Appender APPENDER = new AppenderSkeleton() {
        {
            setName("skidfuscator-progress-renderer");
        }

        @Override
        protected void append(LoggingEvent event) {
            target.print(ansi()
                    .cursorUpLine()
                    .eraseLine(Ansi.Erase.ALL)
                    .append("\r").eraseLine(Ansi.Erase.ALL)
            );
        }

        @Override
        public void close() {

        }

        @Override
        public boolean requiresLayout() {
            return false;
        }
    };

    @Override
    public synchronized void begin(TaskMonitor monitor) {
        try {
            LogManager.getRootLogger().addAppender(APPENDER);
        } catch (LinkageError | RuntimeException ignored) {
            // Gradle can provide a log4j bridge whose Appender type is not compatible
            // with the real log4j classes Skidfuscator is compiled against.
        }
    }

    @Override
    public synchronized void render() {
        StringBuilder content = buildAnsiString();
        String string = content.toString();

        // move cursor up
        if (lines > 0) {
            //target.print(ansi().eraseLine(Ansi.Erase.ALL));
            //target.print(ANSI_CSI + (lines) + "A"); // up xx lines
        }

        target.print(ansi()
                .cursorUpLine()
                .eraseLine(Ansi.Erase.ALL)
                .append(string)
                .newline()
        );
        //target.print(ansi().eraseLine(Ansi.Erase.ALL));
        //target.print("\r");
        //target.println(ansi().scrollUp(1).a(string));

        lines = countLines(string);
    }

    @Override
    public synchronized void finish(TaskMonitor monitor) {
        super.finish(monitor);
        System.out.println();
        try {
            LogManager.getRootLogger().removeAppender(APPENDER);
        } catch (LinkageError | RuntimeException ignored) {
            try {
                LogManager.getRootLogger().removeAppender(APPENDER.getName());
            } catch (LinkageError | RuntimeException ignoredAgain) {
                // Best-effort cleanup only; obfuscation must not fail on logger bridges.
            }
        }
    }

    public StringBuilder buildAnsiString() {

        StringBuilder buffer = new StringBuilder();

        for (TaskMonitor monitor : monitors) {

            if (monitor != monitors.get(0)) {
                buffer.append("\n");
            }

            if (components != null) {
                for (IProgressIndicator component : components) {
                    if (component != null) {
                        component.render(monitor, buffer);
                    }
                }
            }

        }

        return buffer;

    }

    private static int countLines(String str) {
        String[] lines = str.split("\r\n|\r|\n");
        return lines.length;
    }

}

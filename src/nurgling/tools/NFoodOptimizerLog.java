package nurgling.tools;

import nurgling.NUtils;

import java.io.IOException;
import java.io.UncheckedIOException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.StandardOpenOption;
import java.time.LocalDateTime;
import java.time.format.DateTimeFormatter;

/**
 * One append-only debug log per table-eating-optimizer session, written to the
 * Haven &amp; Hearth shared data folder (via {@link NUtils#getDataFilePath}) under a
 * {@code logs/} subfolder, one new timestamped file per run.
 * <p>
 * Deliberately its own standalone file (touches nothing else in the tree) so it stays
 * out of the way of anything upstream.
 */
public class NFoodOptimizerLog implements AutoCloseable
{
    private static final DateTimeFormatter FILE_STAMP = DateTimeFormatter.ofPattern("yyyyMMdd-HHmmss");
    private static final DateTimeFormatter LINE_STAMP = DateTimeFormatter.ofPattern("HH:mm:ss.SSS");

    private final Path path;

    public NFoodOptimizerLog()
    {
        Path dir = NUtils.getDataFilePath("logs");
        try
        {
            Files.createDirectories(dir);
        }
        catch (IOException e)
        {
            throw new UncheckedIOException(e);
        }
        this.path = dir.resolve("fep-optimizer-" + LocalDateTime.now().format(FILE_STAMP) + ".log");
    }

    public void log(String fmt, Object... args)
    {
        String line = "[" + LocalDateTime.now().format(LINE_STAMP) + "] " + String.format(fmt, args) + System.lineSeparator();
        try
        {
            Files.writeString(path, line, StandardOpenOption.CREATE, StandardOpenOption.APPEND);
        }
        catch (IOException e)
        {
            System.err.println("[NFoodOptimizerLog] failed to write " + path + ": " + e.getMessage());
        }
    }

    public Path path()
    {
        return path;
    }

    @Override
    public void close()
    {
        // Nothing to release -- each log() call opens/appends/closes its own stream so
        // a crash mid-session never leaves a truncated or locked file.
    }
}

package nurgling.tools;

import nurgling.NUtils;

import java.io.FileWriter;
import java.io.IOException;
import java.io.PrintWriter;
import java.nio.file.Path;
import java.text.SimpleDateFormat;
import java.util.Date;

/**
 * Plain-text file logging for the frontier-exploration bots (WorldExplorer
 * et al.). Game chat has no select-all and no scrollbar drag - a real
 * testing session confirmed it becomes physically impossible to copy out
 * once a bot has been running a while (the user could not complete a
 * click-hold-and-scroll selection before the client became unresponsive).
 * This is a plain file instead: open it in any text editor, no client
 * interaction needed to read or copy it.
 *
 * log() is file-only, for the high-frequency per-iteration diagnostic detail
 * (every target pick, every failed route attempt). logAndChat() also does
 * the existing gui.msg() call, for the sparse, glanceable events (bot
 * start/stop, stuck notifications) worth seeing in-client without opening
 * the file.
 */
public class NDebugLog
{
    private static final String FILE_PREFIX = "worldexplorer-debug";
    private static Path resolvedPath = null;

    /**
     * Starts a fresh, timestamped log file for one bot run. Previously every
     * run appended to a single file, so isolating the run you actually cared
     * about meant hunting for the right "starting..." line in a file
     * containing every earlier session.
     */
    public static void newRun()
    {
        resolvedPath = NUtils.getDataFilePath(FILE_PREFIX + "-"
                + new SimpleDateFormat("yyyyMMdd-HHmmss").format(new Date()) + ".log");
    }

    public static void log(String msg)
    {
        try
        {
            if (resolvedPath == null)
                newRun();
            String line = new SimpleDateFormat("HH:mm:ss.SSS").format(new Date()) + " " + msg;
            try (PrintWriter out = new PrintWriter(new FileWriter(resolvedPath.toFile(), true)))
            {
                out.println(line);
            }
        }
        catch (IOException | RuntimeException e)
        {
            // Best-effort - fall back to chat if the file write itself fails,
            // so a broken log path doesn't silently swallow diagnostics.
            if (NUtils.getGameUI() != null)
                NUtils.getGameUI().msg("NDebugLog: failed to write log file (" + e.getMessage() + "): " + msg);
        }
    }

    public static void logAndChat(String msg)
    {
        log(msg);
        if (NUtils.getGameUI() != null)
            NUtils.getGameUI().msg(msg);
    }

    public static String path()
    {
        if (resolvedPath == null)
            newRun();
        return resolvedPath.toString();
    }
}

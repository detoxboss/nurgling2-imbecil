package nurgling.watch;

import org.json.JSONObject;

import java.io.IOException;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.StandardCopyOption;
import java.util.ArrayList;
import java.util.List;
import java.util.Map;
import java.util.TreeMap;

/**
 * Named watcher profiles in one JSON file. Every call re-reads the file, so a client that starts a
 * lookout sees profiles another client window saved a moment ago, and writes replace the file
 * atomically. Profiles handed out are copies: nothing a caller does to one changes what is stored,
 * or what another running lookout is using.
 *
 * <p>Also remembers which profile each <em>character</em> last used, so starting the bot from the
 * Bots menu (which carries no settings) resumes the right lookout for that character.
 */
public final class WatchProfileStore {
    private static final Object LOCK = new Object();
    private static WatchProfileStore shared;

    private final Path file;

    public WatchProfileStore(Path file) {
        this.file = file;
    }

    /** The store the game uses: {@code village_watch.nurgling.json} beside the other nurgling data files. */
    public static WatchProfileStore shared() {
        synchronized (LOCK) {
            if (shared == null)
                shared = new WatchProfileStore(nurgling.NUtils.getDataFilePath("village_watch.nurgling.json"));
            return shared;
        }
    }

    private static final class State {
        final Map<String, WatchProfile> profiles = new TreeMap<>(String.CASE_INSENSITIVE_ORDER);
        final Map<String, String> characters = new TreeMap<>();
    }

    private State read() {
        State s = new State();
        if (Files.isRegularFile(file)) {
            try {
                JSONObject root = new JSONObject(new String(Files.readAllBytes(file), StandardCharsets.UTF_8));
                JSONObject profiles = root.optJSONObject("profiles");
                if (profiles != null) {
                    for (String key : profiles.keySet()) {
                        if (WatchProfile.validName(key))
                            s.profiles.put(key, WatchProfile.fromJson(key, profiles.optJSONObject(key)));
                    }
                }
                JSONObject chars = root.optJSONObject("characters");
                if (chars != null) {
                    for (String key : chars.keySet())
                        s.characters.put(key, chars.optString(key));
                }
            } catch (IOException | RuntimeException e) {
                // A corrupt or unreadable file reads as empty; the next save replaces it.
                System.err.println("[VillageWatch] could not read " + file.getFileName() + ": " + e.getMessage());
            }
        }
        if (!s.profiles.containsKey(WatchProfile.DEFAULT_NAME))
            s.profiles.put(WatchProfile.DEFAULT_NAME, new WatchProfile(WatchProfile.DEFAULT_NAME));
        return s;
    }

    private void write(State s) throws IOException {
        JSONObject root = new JSONObject();
        root.put("version", 1);
        JSONObject profiles = new JSONObject();
        for (Map.Entry<String, WatchProfile> e : s.profiles.entrySet())
            profiles.put(e.getKey(), e.getValue().toJson());
        root.put("profiles", profiles);
        JSONObject chars = new JSONObject();
        for (Map.Entry<String, String> e : s.characters.entrySet())
            chars.put(e.getKey(), e.getValue());
        root.put("characters", chars);

        Path dir = file.toAbsolutePath().getParent();
        if (dir != null)
            Files.createDirectories(dir);
        Path tmp = file.resolveSibling(file.getFileName() + ".tmp");
        Files.write(tmp, root.toString(2).getBytes(StandardCharsets.UTF_8));
        try {
            Files.move(tmp, file, StandardCopyOption.REPLACE_EXISTING, StandardCopyOption.ATOMIC_MOVE);
        } catch (IOException atomicUnsupported) {
            Files.move(tmp, file, StandardCopyOption.REPLACE_EXISTING);
        }
    }

    public List<String> names() {
        synchronized (LOCK) {
            return new ArrayList<>(read().profiles.keySet());
        }
    }

    /** A copy of the named profile, or null when there is none (the Default profile always exists). */
    public WatchProfile get(String name) {
        if (name == null)
            return null;
        synchronized (LOCK) {
            WatchProfile p = read().profiles.get(name);
            return p == null ? null : p.copy();
        }
    }

    public void save(WatchProfile profile) throws IOException {
        if (!WatchProfile.validName(profile.name))
            throw new IOException("Invalid profile name");
        synchronized (LOCK) {
            State s = read();
            // Re-key under the stored spelling so "south wall" cannot sit beside "South Wall".
            String existing = null;
            for (String k : s.profiles.keySet()) {
                if (k.equalsIgnoreCase(profile.name))
                    existing = k;
            }
            if (existing != null)
                s.profiles.remove(existing);
            WatchProfile stored = profile.copy().normalized();
            stored.name = profile.name.trim();
            s.profiles.put(stored.name, stored);
            write(s);
        }
    }

    /** Removes a profile and any character assignment pointing at it. The Default profile cannot be deleted. */
    public boolean delete(String name) throws IOException {
        if (name == null || WatchProfile.DEFAULT_NAME.equalsIgnoreCase(name))
            return false;
        synchronized (LOCK) {
            State s = read();
            if (s.profiles.remove(name) == null)
                return false;
            s.characters.values().removeIf(v -> v.equalsIgnoreCase(name));
            write(s);
            return true;
        }
    }

    /** The profile this character last used, or {@link WatchProfile#DEFAULT_NAME}. */
    public String profileFor(String character) {
        synchronized (LOCK) {
            State s = read();
            String name = character == null ? null : s.characters.get(character);
            return (name != null && s.profiles.containsKey(name)) ? name : WatchProfile.DEFAULT_NAME;
        }
    }

    public void assign(String character, String profileName) throws IOException {
        if (character == null || character.isEmpty())
            return;
        synchronized (LOCK) {
            State s = read();
            if (!s.profiles.containsKey(profileName))
                return;
            if (profileName.equals(s.characters.get(character)))
                return;
            s.characters.put(character, profileName);
            write(s);
        }
    }
}

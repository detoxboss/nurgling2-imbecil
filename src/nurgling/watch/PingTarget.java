package nurgling.watch;

import org.json.JSONArray;
import org.json.JSONObject;

import java.util.regex.Pattern;

/**
 * Who a Village Watch alert notifies. Discord only notifies from a message's top-level
 * {@code content}, never from an embed, and only for what {@code allowed_mentions} permits - so the
 * mention text and its permission are generated together here, from a fixed token plus a validated
 * snowflake ID. Nothing a player or template supplies can reach either.
 */
public enum PingTarget {
    NONE("none", "No ping"),
    HERE("here", "@here"),
    EVERYONE("everyone", "@everyone"),
    ROLE("role", "Specific role"),
    USER("user", "Specific user");

    private static final Pattern SNOWFLAKE = Pattern.compile("\\d{17,20}");

    public final String key;
    public final String label;

    PingTarget(String key, String label) {
        this.key = key;
        this.label = label;
    }

    public boolean needsId() {
        return this == ROLE || this == USER;
    }

    public static boolean validId(String id) {
        return id != null && SNOWFLAKE.matcher(id.trim()).matches();
    }

    /** True when this target can actually be used with the given ID. */
    public boolean usable(String id) {
        return this != NONE && (!needsId() || validId(id));
    }

    public static PingTarget fromKey(String key) {
        for (PingTarget t : values()) {
            if (t.key.equals(key))
                return t;
        }
        return NONE;
    }

    /** The mention token for message content; null when this target is not usable. */
    public String mention(String id) {
        if (!usable(id))
            return null;
        switch (this) {
            case HERE:
                return "@here";
            case EVERYONE:
                return "@everyone";
            case ROLE:
                return "<@&" + id.trim() + ">";
            default:
                return "<@" + id.trim() + ">";
        }
    }

    /** The {@code allowed_mentions} object that permits exactly this target and nothing else. */
    public JSONObject allowedMentions(String id) {
        JSONObject allowed = new JSONObject();
        JSONArray parse = new JSONArray();
        if (usable(id)) {
            if (this == HERE || this == EVERYONE)
                parse.put("everyone");
            else if (this == ROLE)
                allowed.put("roles", new JSONArray().put(id.trim()));
            else
                allowed.put("users", new JSONArray().put(id.trim()));
        }
        allowed.put("parse", parse);
        return allowed;
    }
}

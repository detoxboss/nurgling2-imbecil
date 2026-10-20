package nurgling.notifications;

import org.json.JSONArray;
import org.json.JSONObject;

import java.awt.Color;
import java.io.ByteArrayOutputStream;
import java.nio.charset.StandardCharsets;
import java.util.ArrayList;
import java.util.List;

/**
 * One webhook message: an embed, optionally with an image attached as a file. The JSON itself is
 * built by {@link DiscordHookObject#toPayload()} so text-only and attachment messages share one
 * payload format; this class adds the {@code multipart/form-data} framing Discord requires for
 * files ({@code payload_json} plus {@code files[n]}) and shows the file inside the embed through an
 * {@code attachment://} reference.
 */
public final class DiscordMessage {
    public static final int MAX_TITLE = 256;
    public static final int MAX_DESCRIPTION = 4000;
    public static final int MAX_FIELD_VALUE = 1000;
    public static final int MAX_FOOTER = 2000;

    public static final class Attachment {
        public final String filename;
        public final String contentType;
        public final byte[] data;

        public Attachment(String filename, String contentType, byte[] data) {
            this.filename = sanitizeFilename(filename);
            this.contentType = contentType;
            this.data = data;
        }
    }

    public String username;
    public String avatarUrl;
    public String title;
    public String description;
    /** Top-level message text; the only part of a message that can notify. Must never carry untrusted text. */
    public String content;
    /** Discord {@code allowed_mentions} for {@link #content}; null means nothing may be mentioned. */
    public JSONObject allowedMentions;
    public int color = 0xE74C3C;
    public String footer;
    /** ISO-8601 instant shown by Discord in the embed footer area. */
    public String timestamp;
    public Attachment attachment;
    private final List<String[]> fields = new ArrayList<>();

    public DiscordMessage addField(String name, String value, boolean inline) {
        fields.add(new String[]{name, value, Boolean.toString(inline)});
        return this;
    }

    /** The JSON body. With {@code withAttachment} the embed shows the attached file. */
    public JSONObject payload(boolean withAttachment) {
        DiscordHookObject hook = new DiscordHookObject();
        hook.setUsername(blankToNull(username));
        hook.setAvatarUrl(blankToNull(avatarUrl));
        DiscordHookObject.EmbedObject embed = new DiscordHookObject.EmbedObject()
                .setTitle(cut(title, MAX_TITLE))
                .setDescription(cut(description, MAX_DESCRIPTION))
                .setColor(new Color(color));
        for (String[] f : fields)
            embed.addField(cut(f[0], MAX_TITLE), cut(f[1], MAX_FIELD_VALUE), Boolean.parseBoolean(f[2]));
        if (footer != null && !footer.isEmpty())
            embed.setFooter(cut(footer, MAX_FOOTER), null);
        if (withAttachment && attachment != null)
            embed.setImage("attachment://" + attachment.filename);
        hook.addEmbed(embed);

        // toPayload() leaves embeds/fields as plain arrays, which only serialize correctly; a text
        // round trip turns them into JSONArrays so they can be amended below.
        JSONObject json = new JSONObject(hook.toPayload().toString());
        if (timestamp != null)
            json.getJSONArray("embeds").getJSONObject(0).put("timestamp", timestamp);
        // Top-level content is the only place Discord notifies from. It stays unset unless the caller
        // supplies a generated mention, and allowed_mentions then permits only that mention.
        if (content != null && !content.isEmpty())
            json.put("content", content);
        json.put("allowed_mentions", allowedMentions != null ? new JSONObject(allowedMentions.toString())
                : new JSONObject().put("parse", new JSONArray()));
        if (withAttachment && attachment != null)
            json.put("attachments", new JSONArray().put(new JSONObject().put("id", 0).put("filename", attachment.filename)));
        return json;
    }

    public boolean hasAttachment() {
        return attachment != null;
    }

    /** Content-Type for a request built by {@link #body}. */
    public String contentType(boolean withAttachment, String boundary) {
        return (withAttachment && attachment != null) ? "multipart/form-data; boundary=" + boundary : "application/json";
    }

    public byte[] body(boolean withAttachment, String boundary) {
        byte[] json = payload(withAttachment).toString().getBytes(StandardCharsets.UTF_8);
        if (!withAttachment || attachment == null)
            return json;
        ByteArrayOutputStream out = new ByteArrayOutputStream(json.length + attachment.data.length + 512);
        write(out, "--" + boundary + "\r\n"
                + "Content-Disposition: form-data; name=\"payload_json\"\r\n"
                + "Content-Type: application/json\r\n\r\n");
        out.write(json, 0, json.length);
        write(out, "\r\n--" + boundary + "\r\n"
                + "Content-Disposition: form-data; name=\"files[0]\"; filename=\"" + attachment.filename + "\"\r\n"
                + "Content-Type: " + attachment.contentType + "\r\n\r\n");
        out.write(attachment.data, 0, attachment.data.length);
        write(out, "\r\n--" + boundary + "--\r\n");
        return out.toByteArray();
    }

    private static void write(ByteArrayOutputStream out, String s) {
        byte[] b = s.getBytes(StandardCharsets.UTF_8);
        out.write(b, 0, b.length);
    }

    static String sanitizeFilename(String name) {
        String clean = name == null ? "" : name.replaceAll("[^A-Za-z0-9._-]", "_");
        return clean.isEmpty() ? "attachment" : clean;
    }

    private static String blankToNull(String s) {
        return (s == null || s.trim().isEmpty()) ? null : s.trim();
    }

    private static String cut(String s, int max) {
        if (s == null)
            return null;
        return s.length() <= max ? s : s.substring(0, max - 3) + "...";
    }
}

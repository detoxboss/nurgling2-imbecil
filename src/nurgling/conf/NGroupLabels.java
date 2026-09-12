package nurgling.conf;

import nurgling.NConfig;

import java.util.HashMap;
import java.util.Map;

/**
 * Client-side custom labels for numeric Kin/Village permission groups (e.g. group 7 = "Farmers").
 *
 * Presentation only: never touches the group id itself, a buddy's name, or any server message.
 * Kin and Village labels are kept in separate {@link NConfig.Key} slots so the same numeric group
 * id can mean different things in each context without one overwriting the other.
 *
 * Persistence scope follows {@link NConfig}'s existing per-world ("genus") profile resolution -
 * the same mechanism every other per-group Kin setting (see {@link NKinProp}) already relies on -
 * so labels set while playing on one world don't leak into another, and every session on that
 * world sees the same labels without introducing a new ambient-global lookup.
 */
public class NGroupLabels {
    public enum Scope {KIN, VILLAGE}

    public static String get(Scope scope, int group) {
        Object v = NConfig.getAsMap(key(scope)).get(Integer.toString(group));
        return (v instanceof String) ? (String) v : "";
    }

    public static void set(Scope scope, int group, String label) {
        NConfig.Key key = key(scope);
        Map<String, Object> labels = new HashMap<>(NConfig.getAsMap(key));
        String trimmed = (label == null) ? "" : label.trim();
        String id = Integer.toString(group);
        if (trimmed.isEmpty()) {
            if (labels.remove(id) == null)
                return;
        } else {
            if (trimmed.equals(labels.get(id)))
                return;
            labels.put(id, trimmed);
        }
        NConfig.set(key, labels);
    }

    private static NConfig.Key key(Scope scope) {
        return (scope == Scope.VILLAGE) ? NConfig.Key.villageGroupLabels : NConfig.Key.kinGroupLabels;
    }
}

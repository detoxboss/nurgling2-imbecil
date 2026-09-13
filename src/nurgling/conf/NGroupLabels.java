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
 * <p>Each scope is additionally keyed by an {@code owner} string, nested one level inside that
 * per-world map, so two owners on the same world never share a namespace:
 * <ul>
 * <li>{@link Scope#KIN} - the owning character's stable id ({@code haven.GameUI#chrid}), so two
 * characters played on the same world can give the same Kin group number different meanings.</li>
 * <li>{@link Scope#VILLAGE} - the village's own {@code Polity.name}. This is the most stable identity
 * available from this seam - the wire protocol exposes no durable numeric village id to the client -
 * so it is a deliberate tradeoff, not an oversight: renaming a village orphans its old labels, and two
 * differently-charter'd villages sharing an identical name on one world would share a namespace. Both
 * are judged rare enough, and low-enough-stakes (client-side presentation, not data loss), to accept
 * rather than block the feature on a village id the protocol does not give us.</li>
 * </ul>
 *
 * <p>Persistence scope otherwise follows {@link NConfig}'s existing per-world ("genus") profile
 * resolution - the same mechanism every other per-group Kin setting (see {@code NKinProp}) already
 * relies on - so labels set while playing on one world don't leak into another, and every session on
 * that world sees the same labels without introducing a new ambient-global lookup.
 */
public class NGroupLabels {
    public enum Scope {KIN, VILLAGE}

    public static String get(Scope scope, String owner, int group) {
        Object ownerMap = NConfig.getAsMap(key(scope)).get(owner);
        if(!(ownerMap instanceof Map))
            return("");
        Object v = ((Map<?, ?>) ownerMap).get(Integer.toString(group));
        return (v instanceof String) ? (String) v : "";
    }

    @SuppressWarnings("unchecked")
    public static void set(Scope scope, String owner, int group, String label) {
        NConfig.Key key = key(scope);
        Map<String, Object> byOwner = new HashMap<>(NConfig.getAsMap(key));
        Object existing = byOwner.get(owner);
        Map<String, Object> labels = new HashMap<>((existing instanceof Map) ? (Map<String, Object>) existing : Map.of());
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
        if (labels.isEmpty())
            byOwner.remove(owner);
        else
            byOwner.put(owner, labels);
        NConfig.set(key, byOwner);
    }

    private static NConfig.Key key(Scope scope) {
        return (scope == Scope.VILLAGE) ? NConfig.Key.villageGroupLabels : NConfig.Key.kinGroupLabels;
    }
}

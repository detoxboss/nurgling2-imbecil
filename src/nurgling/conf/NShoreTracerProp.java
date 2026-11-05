package nurgling.conf;

import nurgling.NConfig;
import nurgling.NUI;
import nurgling.NUtils;
import org.json.JSONObject;

import java.util.ArrayList;
import java.util.HashMap;
import java.util.Iterator;
import java.util.List;

/**
 * Fork's own coastline-tracing bot ("Shore Tracer"), split out from the shared
 * WorldExplorer bot id/class on 2026-11-05 when upstream rewrote WorldExplorer
 * as an independent ray-sweep implementation ("Ocean Explorer"). This class is
 * the renamed continuation of the fork's pre-split NWorldExplorerProp shape;
 * see NConfig.read()'s one-time migration of legacy worldexplorerprop entries
 * for how existing saved settings carry forward.
 */
public class NShoreTracerProp implements JConf
{
    final private String username;
    final private String chrid;
    public boolean clockwise = false;
    public boolean deeper = true;

    // Boundary-following tunables (see ShoreTracer).
    // How far (in tiles) ShoreTracerMove.scanAhead extends a single click
    // along the water/deep boundary before stopping, i.e. the momentum
    // lookahead - bigger means fewer, longer clicks. Also reused as the ray
    // distance for the rare "boxed in on all 4 directions" fallback.
    public int lookaheadTiles = 80;
    public double stuckTimeoutS = 2.0;
    public int backupTiles = 2;
    public int swingTiles = 5;
    // How many tiles off the water/deep boundary the hug is allowed to drift
    // (a shallow-water tile must exist within this many tiles, perpendicular
    // to the current heading, at every step of a run) - keeps the boat
    // hugging the shoreline itself rather than the middle of open water.
    public int bandTiles = 5;

    // Persisted cross-session memory of visited MCache.Grid.id values, so a
    // resumed run doesn't immediately re-explore the same water. Grid.id
    // (not grid coordinate) is the stable, session-independent identity -
    // see ShoreTracerFrontier's class doc.
    public ArrayList<Long> visitedGridIds = new ArrayList<>();

    public NShoreTracerProp(String username, String chrid) {
        this.username = username;
        this.chrid = chrid;
    }

    @SuppressWarnings("unchecked")
    public NShoreTracerProp(HashMap<String, Object> values)
    {
        chrid = (String) values.get("chrid");
        username = (String) values.get("username");
        if (values.get("clockwise") != null)
            clockwise = (Boolean) values.get("clockwise");
        if (values.get("deeper") != null)
            deeper = (Boolean) values.get("deeper");
        if (values.get("lookaheadTiles") != null)
            lookaheadTiles = ((Number) values.get("lookaheadTiles")).intValue();
        if (values.get("stuckTimeoutS") != null)
            stuckTimeoutS = ((Number) values.get("stuckTimeoutS")).doubleValue();
        if (values.get("backupTiles") != null)
            backupTiles = ((Number) values.get("backupTiles")).intValue();
        if (values.get("swingTiles") != null)
            swingTiles = ((Number) values.get("swingTiles")).intValue();
        if (values.get("bandTiles") != null)
            bandTiles = ((Number) values.get("bandTiles")).intValue();
        Object rawVisited = values.get("visitedGridIds");
        if (rawVisited instanceof List)
        {
            for (Object o : (List<Object>) rawVisited)
                if (o instanceof Number)
                    visitedGridIds.add(((Number) o).longValue());
        }
    }

    public static void set(NShoreTracerProp prop)
    {
        ArrayList<NShoreTracerProp> props = ((ArrayList<NShoreTracerProp>) NConfig.get(NConfig.Key.shoretracerprop));
        if (props != null)
        {
            for (Iterator<NShoreTracerProp> i = props.iterator(); i.hasNext(); )
            {
                NShoreTracerProp oldprop = i.next();
                if(oldprop.username.equals(prop.username) && oldprop.chrid.equals(prop.chrid))
                {
                    i.remove();
                    break;
                }
            }

        }
        else
        {
            props = new ArrayList<>();
        }
        props.add(prop);
        NConfig.set(NConfig.Key.shoretracerprop, props);
    }

    @Override
    public String toString()
    {
        return "NShoreTracer[" + username + "|" + chrid + "]";
    }

    @Override
    public JSONObject toJson()
    {
        JSONObject jexplorer = new JSONObject();
        jexplorer.put("type", "NShoreTracer");
        jexplorer.put("username", username);
        jexplorer.put("chrid", chrid);
        jexplorer.put("clockwise", clockwise);
        jexplorer.put("deeper", deeper);
        jexplorer.put("lookaheadTiles", lookaheadTiles);
        jexplorer.put("stuckTimeoutS", stuckTimeoutS);
        jexplorer.put("backupTiles", backupTiles);
        jexplorer.put("swingTiles", swingTiles);
        jexplorer.put("bandTiles", bandTiles);
        jexplorer.put("visitedGridIds", visitedGridIds);
        return jexplorer;
    }

    @SuppressWarnings("unchecked")
    public static NShoreTracerProp get(NUI.NSessInfo sessInfo)
    {
        if (sessInfo == null || NUtils.getGameUI() == null || NUtils.getGameUI().getCharInfo() == null)
            return null;
        String chrid = NUtils.getGameUI().getCharInfo().chrid;
        ArrayList<NShoreTracerProp> props = ((ArrayList<NShoreTracerProp>) NConfig.get(NConfig.Key.shoretracerprop));
        if (props == null)
            props = new ArrayList<>();
        for (NShoreTracerProp prop : props)
        {
            if (prop.username.equals(sessInfo.username) && prop.chrid.equals(chrid))
            {
                return prop;
            }
        }
        return new NShoreTracerProp(sessInfo.username, chrid);
    }
}

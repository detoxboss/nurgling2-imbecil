package nurgling.conf;

import nurgling.NConfig;
import nurgling.NUI;
import nurgling.NUtils;
import org.json.JSONObject;

import java.util.ArrayList;
import java.util.HashMap;
import java.util.Iterator;
import java.util.List;


public class NWorldExplorerProp implements JConf
{
    final private String username;
    final private String chrid;
    public boolean clockwise = false;
    public boolean deeper = true;

    // Boundary-following tunables (see WorldExplorer rewrite).
    // How far (in tiles) WorldExplorerMove.scanAhead extends a single click
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
    // see WorldExplorerFrontier's class doc.
    public ArrayList<Long> visitedGridIds = new ArrayList<>();

    public NWorldExplorerProp(String username, String chrid) {
        this.username = username;
        this.chrid = chrid;
    }

    @SuppressWarnings("unchecked")
    public NWorldExplorerProp(HashMap<String, Object> values)
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

    public static void set(NWorldExplorerProp prop)
    {
        ArrayList<NWorldExplorerProp> explorerProps = ((ArrayList<NWorldExplorerProp>) NConfig.get(NConfig.Key.worldexplorerprop));
        if (explorerProps != null)
        {
            for (Iterator<NWorldExplorerProp> i = explorerProps.iterator(); i.hasNext(); )
            {
                NWorldExplorerProp oldprop = i.next();
                if(oldprop.username.equals(prop.username) && oldprop.chrid.equals(prop.chrid))
                {
                    i.remove();
                    break;
                }
            }

        }
        else
        {
            explorerProps = new ArrayList<>();
        }
        explorerProps.add(prop);
        NConfig.set(NConfig.Key.worldexplorerprop, explorerProps);
    }

    @Override
    public String toString()
    {
        return "NWorldExplorer[" + username + "|" + chrid + "]";
    }

    @Override
    public JSONObject toJson()
    {
        JSONObject jexplorer = new JSONObject();
        jexplorer.put("type", "NWorldExplorer");
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

    public static NWorldExplorerProp get(NUI.NSessInfo sessInfo)
    {
        if (sessInfo == null || NUtils.getGameUI() == null || NUtils.getGameUI().getCharInfo() == null)
            return null;
        String chrid = NUtils.getGameUI().getCharInfo().chrid;
        ArrayList<NWorldExplorerProp> worldexpProps = ((ArrayList<NWorldExplorerProp>) NConfig.get(NConfig.Key.worldexplorerprop));
        if (worldexpProps == null)
            worldexpProps = new ArrayList<>();
        for (NWorldExplorerProp prop : worldexpProps)
        {
            if (prop.username.equals(sessInfo.username) && prop.chrid.equals(chrid))
            {
                return prop;
            }
        }
        return new NWorldExplorerProp(sessInfo.username, chrid);
    }
}

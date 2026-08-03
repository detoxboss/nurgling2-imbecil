package nurgling.conf;

import nurgling.NConfig;
import nurgling.NUI;
import nurgling.NUtils;
import org.json.JSONObject;

import java.util.ArrayList;
import java.util.HashMap;
import java.util.Iterator;

public class NLpAssistantProp implements JConf
{
    final private String username;
    final private String chrid;
    public String boardTool = null;
    public String blockTool = null;
    public String stoneTool = null;
    public String oldtrunkTool = null;
    public boolean autoEatNew = true;
    public boolean autoDropNew = true;
    public boolean debug = false;

    public NLpAssistantProp(String username, String chrid) {
        this.username = username;
        this.chrid = chrid;
    }

    public NLpAssistantProp(HashMap<String, Object> values)
    {
        chrid = (String) values.get("chrid");
        username = (String) values.get("username");
        if (values.get("boardTool") != null)
            boardTool = (String) values.get("boardTool");
        if (values.get("blockTool") != null)
            blockTool = (String) values.get("blockTool");
        if (values.get("stoneTool") != null)
            stoneTool = (String) values.get("stoneTool");
        if (values.get("oldtrunkTool") != null)
            oldtrunkTool = (String) values.get("oldtrunkTool");
        if (values.get("autoEatNew") != null)
            autoEatNew = (Boolean) values.get("autoEatNew");
        if (values.get("autoDropNew") != null)
            autoDropNew = (Boolean) values.get("autoDropNew");
        if (values.get("debug") != null)
            debug = (Boolean) values.get("debug");
    }

    public static void set(NLpAssistantProp prop)
    {
        ArrayList<NLpAssistantProp> props = ((ArrayList<NLpAssistantProp>) NConfig.get(NConfig.Key.lpassistantbotprop));
        if (props != null)
        {
            for (Iterator<NLpAssistantProp> i = props.iterator(); i.hasNext(); )
            {
                NLpAssistantProp oldprop = i.next();
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
        NConfig.set(NConfig.Key.lpassistantbotprop, props);
    }

    @Override
    public String toString()
    {
        return "NLpAssistantProp[" + username + "|" + chrid + "]";
    }

    @Override
    public JSONObject toJson()
    {
        JSONObject j = new JSONObject();
        j.put("type", "NLpAssistantProp");
        j.put("username", username);
        j.put("chrid", chrid);
        j.put("boardTool", boardTool);
        j.put("blockTool", blockTool);
        j.put("stoneTool", stoneTool);
        j.put("oldtrunkTool", oldtrunkTool);
        j.put("autoEatNew", autoEatNew);
        j.put("autoDropNew", autoDropNew);
        j.put("debug", debug);
        return j;
    }

    public static NLpAssistantProp get(NUI.NSessInfo sessInfo)
    {
        if (sessInfo == null || NUtils.getGameUI() == null || NUtils.getGameUI().getCharInfo() == null)
            return null;
        String chrid = NUtils.getGameUI().getCharInfo().chrid;
        ArrayList<NLpAssistantProp> props = ((ArrayList<NLpAssistantProp>) NConfig.get(NConfig.Key.lpassistantbotprop));
        if (props == null)
            props = new ArrayList<>();
        for (NLpAssistantProp prop : props)
        {
            if (prop.username.equals(sessInfo.username) && prop.chrid.equals(chrid))
            {
                return prop;
            }
        }
        return new NLpAssistantProp(sessInfo.username, chrid);
    }
}

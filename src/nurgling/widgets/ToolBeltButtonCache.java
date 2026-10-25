package nurgling.widgets;

import haven.IButton;
import haven.UI;
import nurgling.equipment.EquipmentPreset;
import nurgling.scenarios.Scenario;

import java.awt.image.BufferedImage;
import java.util.HashMap;
import java.util.Iterator;
import java.util.Map;
import java.util.Objects;

/**
 * Keeps the scenario / equipment-preset buttons of one toolbelt between frames.
 *
 * <p>The belt draws these buttons as plain delegates (they are never added to the widget tree), and
 * each one paints a fixed icon. Building a fresh button per frame made every frame redraw the icon
 * into a new image and upload it as a new texture, so one is kept per slot and rebuilt only when
 * what it shows could have changed.
 *
 * <p>A slot is not enough to identify a button: the scenario or preset behind it can be renamed,
 * given another icon, or replaced by a reloaded copy. An entry is therefore reused only while the
 * source object, its name, its icon id, the icon image itself and the UI scale are all unchanged.
 * A custom icon that is regenerated hands out a new image, which is what invalidates it.
 *
 * <p>Owned by a single toolbelt, so nothing is shared between sessions. Not thread-safe: use it
 * from the UI thread, as the belt's draw does.
 */
public class ToolBeltButtonCache {
    private static final class Entry {
        final Object source;
        final String name, iconId;
        final BufferedImage icon;
        final int scale;
        final IButton button;

        Entry(Object source, String name, String iconId, BufferedImage icon, int scale, IButton button) {
            this.source = source;
            this.name = name;
            this.iconId = iconId;
            this.icon = icon;
            this.scale = scale;
            this.button = button;
        }

        boolean matches(Object source, String name, String iconId, BufferedImage icon, int scale) {
            return this.source == source && this.icon == icon && this.scale == scale
                && Objects.equals(this.name, name) && Objects.equals(this.iconId, iconId);
        }
    }

    private final Map<Integer, Entry> entries = new HashMap<>();
    private int builds = 0;

    public IButton scenario(int slot, Scenario scenario) {
        if(scenario == null) {
            evict(slot);
            return null;
        }
        IButton hit = lookup(slot, scenario, scenario.getName(), scenario.getCustomIconId());
        return (hit != null) ? hit : store(slot, scenario, scenario.getName(), scenario.getCustomIconId(), makeScenario(scenario));
    }

    public IButton preset(int slot, EquipmentPreset preset) {
        if(preset == null) {
            evict(slot);
            return null;
        }
        IButton hit = lookup(slot, preset, preset.getName(), preset.getCustomIconId());
        return (hit != null) ? hit : store(slot, preset, preset.getName(), preset.getCustomIconId(), makePreset(preset));
    }

    /* The hit path runs every frame for every slot, so it allocates nothing. */
    private IButton lookup(int slot, Object source, String name, String iconId) {
        Entry cur = entries.get(slot);
        if((cur != null) && cur.matches(source, name, iconId, customImage(iconId), UI.scale(32)))
            return cur.button;
        return null;
    }

    private IButton store(int slot, Object source, String name, String iconId, IButton button) {
        Entry old = entries.put(slot, new Entry(source, name, iconId, customImage(iconId), UI.scale(32), button));
        if(old != null)
            old.button.dispose();
        builds++;
        return button;
    }

    /** Drop the button of a slot that no longer holds a scenario or preset. */
    public void evict(int slot) {
        if(entries.isEmpty())
            return;
        Entry old = entries.remove(slot);
        if(old != null)
            old.button.dispose();
    }

    /** Free every button, when the belt itself goes away. */
    public void clear() {
        for(Iterator<Entry> i = entries.values().iterator(); i.hasNext();) {
            i.next().button.dispose();
            i.remove();
        }
    }

    /** Buttons built so far; unchanged slots drawn again must not move it. */
    public int builds() {
        return builds;
    }

    public int size() {
        return entries.size();
    }

    /* Overridable so the cache can be exercised without the global icon store or real icon art. */

    protected BufferedImage customImage(String iconId) {
        if(iconId == null)
            return null;
        CustomIcon icon = CustomIconManager.getInstance().getIcon(iconId);
        return (icon == null) ? null : icon.getImage(0);
    }

    protected IButton makeScenario(Scenario scenario) {
        return new NScenarioButton(scenario);
    }

    protected IButton makePreset(EquipmentPreset preset) {
        return new NEquipmentPresetButton(preset);
    }
}

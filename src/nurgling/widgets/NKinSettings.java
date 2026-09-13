package nurgling.widgets;

import haven.*;
import haven.Window;
import nurgling.*;
import nurgling.conf.*;
import nurgling.i18n.L10n;

public class NKinSettings extends Window
{
    final ICheckBox btn;
    BuddyWnd.GroupSelector gs;
    CheckBox ring;
    CheckBox alarm;
    CheckBox arrow;
    CheckBox hil;

    public NKinSettings(ICheckBox btn)
    {
        super(UI.scale(300, 150), L10n.get("kin.notification.title"));
        this.btn = btn;
        prev = add(gs = new BuddyWnd.GroupSelector(0)
        {
            protected void changed(int group)
            {
                loadprop(group);
            }
        });
        prev = add(ring = new CheckBox(L10n.get("kin.notification.highlighting"))
        {

            public void set(boolean val)
            {
                NKinProp prop = new NKinProp(gs.group,alarm.a,arrow.a,val,hil.a);
                NKinProp.set(prop);
                a = val;
            }

        }, prev.pos("bl").adds(0, 5));
        prev = add(arrow = new CheckBox(L10n.get("kin.notification.arrow"))
        {

            public void set(boolean val)
            {
                NKinProp prop = new NKinProp(gs.group,alarm.a,val,ring.a,hil.a);
                NKinProp.set(prop);
                a = val;
            }
        }, prev.pos("bl").adds(0, 5));
        prev = add(alarm = new CheckBox(L10n.get("kin.notification.alarm"))
        {

            public void set(boolean val)
            {
                NKinProp prop = new NKinProp(gs.group,val,arrow.a,ring.a, hil.a);
                NKinProp.set(prop);
                a = val;
            }

        }, prev.pos("bl").adds(0, 5));

        prev = add(hil = new CheckBox(L10n.get("kin.notification.hide"))
        {

            public void set(boolean val)
            {
                NKinProp prop = new NKinProp(gs.group,alarm.a,arrow.a,ring.a,val);
                NKinProp.set(prop);
                a = val;
            }

        }, prev.pos("bl").adds(0, 5));

        loadprop(0);
        pack();
    }

    /** Loads the checkboxes for {@code group}; also {@link BuddyWnd.GroupSelector}'s own changed()
     *  override, called whenever a companion picker (see {@link NGroupSelectorAugmenter}) or a
     *  colour square drives this selector to a new group. */
    private void loadprop(int group)
    {
        NKinProp prop = NKinProp.get(group);
        ring.a = prop.ring;
        alarm.a = prop.alarm;
        arrow.a = prop.arrow;
        hil.a = prop.hideinlist;
    }

    @Override
    public void wdgmsg(String msg, Object... args)
    {
        if(msg.equals("close"))
        {
            hide();
            btn.a = false;
        }
        else
        {
            super.wdgmsg(msg, args);
        }
    }
}

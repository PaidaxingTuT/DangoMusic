import java.lang.reflect.*;
import java.nio.file.*;
import java.util.*;
import me.sorihiko.dango.DangoMusic;
import me.sorihiko.dango.music.*;
import me.sorihiko.dango.module.impls.music.*;
import me.sorihiko.dango.module.values.IntValue;
import me.sorihiko.dango.platform.Platform;
import me.sorihiko.dango.musicui.SettingsPage;

class TransitionSettingsCheck {
    static Object field(Object object, String name) throws Exception {
        Field field = object.getClass().getDeclaredField(name);
        field.setAccessible(true);
        return field.get(object);
    }
    static void set(Object object, String name, Object value) throws Exception {
        Field field = object.getClass().getDeclaredField(name);
        field.setAccessible(true);
        field.set(object,value);
    }
    static void check(boolean value, String message) {
        if (!value) throw new AssertionError(message);
    }
    public static void main(String[] args) throws Exception {
        var directory = Files.createTempDirectory("dango-transition-settings-");
        Platform platform = (Platform) Proxy.newProxyInstance(Platform.class.getClassLoader(), new Class<?>[] {Platform.class},
            (proxy, method, values) -> switch (method.getName()) {
                case "getConfigDir" -> directory;
                case "getHasGameAudio" -> true;
                default -> throw new AssertionError("Unexpected platform call " + method.getName());
            });
        var module = new NeteaseMusicModule();
        DangoMusic.start(platform, List.of(module,new LyricsModule(),new SongCardModule(),new DynamicIslandModule()));
        var screen = new MusicScreen(module, () -> kotlin.Unit.INSTANCE);
        var settings = (SettingsPage) field(screen,"settings");
        Method build = SettingsPage.class.getDeclaredMethod("buildSettings");
        build.setAccessible(true);
        List<?> groups = (List<?>) ((List<?>) build.invoke(settings)).get(1);
        List<?> rows = null;
        for (Object group : groups) if (field(group,"title").equals("Track transition")) rows = (List<?>) field(group,"rows");
        check(rows != null,"missing transition group");
        var mode = (IntValue) field(module,"trackTransition");
        for (var selected : TrackTransitionMode.values()) {
            mode.setValue(selected.ordinal());
            Set<String> visible = new LinkedHashSet<>();
            for (Object row : rows) {
                var predicate = (kotlin.jvm.functions.Function0<?>) field(row,"visible");
                if ((Boolean) predicate.invoke()) visible.add((String)field(row,"key"));
                if (field(row,"key").equals("trackTransition")) check(field(row,"ctl").toString().equals("TRANSITION"),"old stepper control");
            }
            Set<String> expected = new LinkedHashSet<>(List.of("trackTransition"));
            if (selected == TrackTransitionMode.FADE) expected.add("transitionSeconds");
            if (selected == TrackTransitionMode.RHYTHM) expected.add("transitionTempoMatch");
            check(visible.equals(expected),selected+" showed "+visible);
        }
        check(TrackTransitionMode.Companion.getMenuEntries().equals(List.of(
            TrackTransitionMode.RHYTHM,TrackTransitionMode.NATURAL,TrackTransitionMode.FADE,
            TrackTransitionMode.GAPLESS,TrackTransitionMode.OFF)),"wrong dropdown order");
        set(settings,"transitionPicker",mode);
        set(settings,"transitionFocus",0);
        check(settings.keyTyped('\0',208),"Down was not handled");
        check(settings.keyTyped('\0',28),"Return was not handled");
        check(mode.getValue()==TrackTransitionMode.NATURAL.ordinal() && field(settings,"transitionPicker")==null,
            "dropdown keyboard selection failed");
        set(settings,"transitionPicker",mode);
        check(settings.keyTyped('\0',1) && field(settings,"transitionPicker")==null,"Escape failed");
        System.out.println("PASS: EchoMusic option order, mode-specific visibility, dropdown control and keyboard selection");
    }
}

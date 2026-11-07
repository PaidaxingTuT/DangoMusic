import com.google.gson.*;
import me.sorihiko.dango.music.*;
import java.util.*;

class EchoStrategyCheck {
    static final EchoTransitionNative engine = EchoTransitionNative.INSTANCE;
    static final JsonParser parser = new JsonParser();

    static void check(boolean value, String description) {
        if (!value) throw new AssertionError(description);
    }
    static JsonObject analysis(double duration, Double bpm, double leading, double trailing) {
        JsonObject a = new JsonObject();
        a.addProperty("duration_secs", duration);
        a.addProperty("window_start_secs", 0);
        a.addProperty("window_end_secs", duration);
        a.addProperty("leading_silence_secs", leading);
        a.addProperty("trailing_silence_start_secs", trailing);
        a.addProperty("beat_confidence", bpm == null ? 0 : .9);
        if (bpm != null) {
            a.addProperty("bpm", bpm);
            a.addProperty("beat_secs", 60 / bpm);
            a.addProperty("beat_phase_secs", 0);
            a.addProperty("downbeat_phase_secs", 0);
        }
        JsonArray energy = new JsonArray();
        for (int i = 0; i < duration * 10; i++) energy.add(new JsonPrimitive(.1));
        a.add("energy", energy);
        a.add("sections", new JsonArray());
        return a;
    }
    static JsonObject decide(String mode, Double bpmB, boolean matchTempo, boolean manual, double duration) {
        JsonObject request = new JsonObject();
        request.add("a", analysis(duration, 120., .5, duration - 5));
        request.add("b", analysis(duration, bpmB, 1, duration));
        request.addProperty("mode", mode);
        request.addProperty("fade_secs", 5);
        request.addProperty("match_tempo", matchTempo);
        request.addProperty("manual", manual);
        request.addProperty("position_secs", 20);
        String value = engine.decide(request.toString());
        return value.isEmpty() ? null : parser.parse(value).getAsJsonObject();
    }
    static void near(double actual, double expected, String why) {
        check(Math.abs(actual - expected) < 1e-6, why + ": " + actual + " != " + expected);
    }
    static void linearMixer(JsonObject plan) {
        int rate = 8000;
        long mixer = engine.createMixer(plan.toString(), rate, 1, 1, 1);
        try {
            int length = 5 * rate;
            float[] a = new float[length], b = new float[length + rate];
            Arrays.fill(a, .2f);
            Arrays.fill(b, -.2f);
            engine.push(mixer, 0, a);
            engine.push(mixer, 0, null);
            engine.push(mixer, 1, b);
            engine.push(mixer, 1, null);
            int rendered = 0;
            while ((engine.info(mixer) & 1) == 0) {
                float[] out = engine.render(mixer, 1024);
                if (out.length == 0) break;
                for (int i = 0; i < out.length && rendered + i < length; i++) {
                    double expected = .2 * (1 - 2 * (rendered + i) / (double) length);
                    check(Math.abs(out[i] - expected) < 1e-6, "fade must be upstream linear amplitude");
                }
                rendered += out.length;
            }
            check(rendered >= length, "mixer stopped before overlap");
        } finally { engine.release(mixer); }
    }
    static void effectMixer(JsonObject plan) {
        long mixer = engine.createMixer(plan.toString(), 8000, 2, .8f, 1.2f);
        try {
            int frames = (int) Math.ceil(plan.get("overlap_secs").getAsDouble() * 8000);
            float[] a = new float[frames * 2 + 16000], b = new float[frames * 2 + 48000];
            for (int i = 0; i < a.length; i++) a[i] = (float) (.1 * Math.sin(i / 2 * .2));
            for (int i = 0; i < b.length; i++) b[i] = (float) (.1 * Math.sin(i / 2 * .07));
            engine.push(mixer, 0, a);
            engine.push(mixer, 0, null);
            engine.push(mixer, 1, b);
            engine.push(mixer, 1, null);
            long total = 0;
            for (int blocks = 0; blocks < 10000 && (engine.info(mixer) & 1) == 0; blocks++) {
                float[] out = engine.render(mixer, 2048);
                for (float sample : out) check(Float.isFinite(sample), "non-finite effect output");
                total += out.length;
            }
            check((engine.info(mixer) & 1) != 0 && total >= frames * 2L, "effect mixer failed to complete");
        } finally { engine.release(mixer); }
    }

    public static void main(String[] args) {
        check(engine.getAvailable(), "bundled native engine could not load");
        var gapless = decide("gapless", 120., false, false, 100);
        near(gapless.get("a_cut_secs").getAsDouble(), 95, "gapless tail trim");
        near(gapless.get("b_start_secs").getAsDouble(), 1, "gapless head trim");
        near(gapless.get("overlap_secs").getAsDouble(), 0, "gapless never overlaps");

        var fade = decide("fade", 120., false, false, 100);
        near(fade.get("a_cut_secs").getAsDouble(), 95, "fade uses full track end");
        near(fade.get("b_start_secs").getAsDouble(), 0, "fade preserves head");
        check(fade.get("template").isJsonNull(), "fade must not use DJ effects");
        linearMixer(fade);

        var basic = decide("automix-basic", 120., false, false, 100);
        check(basic.get("template").getAsString().equals("simple-exchange"), "compatible natural mix template");
        near(basic.get("overlap_secs").getAsDouble(), 8, "natural mix uses four bars");
        near(basic.get("a_tempo_ratio").getAsDouble(), 1, "natural mix preserves tempo");
        check(decide("automix-basic", 100., false, false, 100).get("template").getAsString().equals("echo-tail"),
            "different tempo must select echo tail");

        var pro = decide("automix-pro", 120., false, false, 100);
        check(pro.get("template").getAsString().equals("filter-eq"), "rhythm mix four-bar template");
        var fallback = decide("automix-pro", null, false, false, 100);
        check(fallback.get("template").getAsString().equals("layered-filter"), "missing grid uses layered filter");
        near(fallback.get("overlap_secs").getAsDouble(), 11, "fallback cut snaps to grid when available");
        var stretch = decide("automix-pro", 126., true, false, 100);
        near(stretch.get("a_tempo_ratio").getAsDouble(), 1.05, "optional SoundTouch tempo ratio");
        check(decide("automix-pro", 126., false, false, 100).get("template").getAsString().equals("layered-filter"),
            "tempo matching disabled uses layered filter");
        var manual = decide("fade", 120., false, true, 100);
        near(manual.get("a_cut_secs").getAsDouble(), 20, "manual starts now");
        near(manual.get("overlap_secs").getAsDouble(), 4, "manual fade capped at four seconds");
        check(decide("fade", 120., false, false, 12) == null, "short fade tracks must not overlap");
        near(decide("automix-basic", 120., false, false, 12).get("overlap_secs").getAsDouble(), 4,
            "short automix uses original gentle fade fallback");

        for (var plan : List.of(basic, pro, fallback, stretch, decide("automix-basic", 100., false, false, 100))) effectMixer(plan);
        System.out.println("PASS: JNI exact cue decisions, distinct DJ templates, manual/short-track rules, linear fade and effect rendering");
    }
}

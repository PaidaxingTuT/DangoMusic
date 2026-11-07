import java.io.*;
import java.net.*;
import java.nio.charset.StandardCharsets;
import java.nio.file.*;
import java.util.*;
import java.util.concurrent.*;
import java.util.function.BooleanSupplier;
import me.sorihiko.dango.DangoMusic;
import me.sorihiko.dango.music.*;
import me.sorihiko.dango.platform.Platform;

class EchoPlaybackCheck {
    static final MusicPlayer player = MusicPlayer.INSTANCE;
    static final List<Output> outputs = new CopyOnWriteArrayList<>();
    static final Map<Integer, byte[]> files = new HashMap<>();
    static volatile int failId, slowId, observedSlowId;
    static final Map<Integer, Integer> requests = new ConcurrentHashMap<>();

    static class Output implements AudioOutput {
        final int rate, channels;
        volatile long written, position, last;
        volatile boolean running, closed;
        volatile int drains;
        Output(int rate, int channels) { this.rate = rate; this.channels = channels; }
        public synchronized long getPositionMicros() {
            long now = System.nanoTime();
            if (running) position = Math.min(written * 1_000_000L / rate, position + (now - last) / 50);
            last = now;
            return position;
        }
        public boolean isRunning() { return running; }
        public boolean getLost() { return false; }
        public void start() { last = System.nanoTime(); running = true; }
        public void stop() { getPositionMicros(); running = false; }
        public int write(byte[] data, int offset, int length) {
            int accepted = Math.min(length, 512 * channels); written += accepted / (2 * channels);
            while (!closed && running && written * 1_000_000L / rate - getPositionMicros() > 160_000) nap(2); return accepted;
        }
        public void drain() {
            drains++;
            while (!closed && getPositionMicros() < written * 1_000_000L / rate) nap(2);
        }
        public void close() { closed = true; }
        public void flush() {}
        public void setVolume(float volume) {}
    }

    static void nap(int millis) {
        try { Thread.sleep(millis); }
        catch (InterruptedException e) { Thread.currentThread().interrupt(); }
    }
    static void check(boolean test, String why) { if (!test) throw new AssertionError(why); }
    static void waitFor(BooleanSupplier test, String why) {
        long until = System.nanoTime() + 15_000_000_000L;
        while (!test.getAsBoolean() && System.nanoTime() < until) nap(2);
        check(test.getAsBoolean(), why + " state=" + player.getState() + " message=" + player.getMessage());
    }
    static NeteaseTrack track(int id) { return new NeteaseTrack(id, "Track " + id, List.of(), id >= 5 ? 40000L : 2000L, ""); }
    static long id() { var current = player.getCurrent(); return current == null ? 0 : current.getId(); }
    static void overlap(TrackTransitionMode mode, long minimumCue) {
        start(mode, 5, 6);
        waitFor(() -> id() == 6, mode + " handoff");
        Output out = outputs.get(0);
        check(outputs.size() == 1 && out.drains == 0, mode + " used a reopened output");
        check(out.getPositionMicros() < 11_000_000, mode + " failed to overlap");
        check(player.getPositionMs() >= minimumCue, mode + " did not start at planned cue");
        check(out.written * 1_000_000L / out.rate > out.getPositionMicros(), mode + " had no queued mixed frames");
        System.out.printf("PASS: %s overlaps at %.2f seconds, incoming position %d ms%n", mode,
            out.getPositionMicros() / 1_000_000.0, player.getPositionMs());
    }

    static void start(TrackTransitionMode mode, int... ids) {
        player.stop();
        outputs.clear();
        requests.clear();
        player.setTransitionMode(mode);
        player.playQueue(Arrays.stream(ids).mapToObj(EchoPlaybackCheck::track).toList(), 0);
        waitFor(() -> id() == ids[0] && player.getState() == MusicPlayer.State.PLAYING, "initial playback");
    }

    public static void main(String[] args) throws Exception {
        Path dir = Path.of(args[0]);
        files.put(5, Files.readAllBytes(dir.resolve("echo-a.flac")));
        files.put(6, Files.readAllBytes(dir.resolve("echo-b.flac")));
        URL.setURLStreamHandlerFactory(protocol -> !protocol.equals("https") ? null : new URLStreamHandler() {
            protected URLConnection openConnection(URL url) {
                return new HttpURLConnection(url) {
                    byte[] body;
                    int code = 200;
                    public void connect() {
                        if (body != null) return;
                        if (url.getHost().equals("interface.music.163.com")) {
                            String rows = String.join(",", files.keySet().stream().map(id ->
                                "{\"id\":" + id + ",\"code\":200,\"type\":\"mp3\",\"url\":\"https://mock.invalid/" + id + "\"}").toList());
                            body = ("{\"code\":200,\"data\":[" + rows + "]}").getBytes(StandardCharsets.UTF_8);
                        } else if (url.getHost().equals("mock.invalid")) {
                            int id = Integer.parseInt(url.getPath().substring(1));
                            int count = requests.merge(id, 1, Integer::sum);
                            if (id == slowId) {
                                observedSlowId = id;
                                nap(4000);
                            }
                            if (id == failId && count == 1) code = 404;
                            body = files.get(id);
                        } else throw new AssertionError("Unexpected URL " + url.getHost() + url.getPath());
                    }
                    public int getResponseCode() { connect(); return code; }
                    public InputStream getInputStream() { connect(); return new ByteArrayInputStream(body); }
                    public OutputStream getOutputStream() { return OutputStream.nullOutputStream(); }
                    public void disconnect() {}
                    public boolean usingProxy() { return false; }
                };
            }
        });
        Platform platform = (Platform) java.lang.reflect.Proxy.newProxyInstance(
            Platform.class.getClassLoader(), new Class<?>[] {Platform.class}, (proxy, method, params) -> {
                switch (method.getName()) {
                    case "getConfigDir": return Files.createTempDirectory(dir, "config-");
                    case "openAudioOutput":
                        Output output = new Output((int) params[0], (int) params[1]);
                        outputs.add(output);
                        return output;
                    default: throw new AssertionError("Unexpected platform method " + method.getName());
                }
            });
        DangoMusic.start(platform, List.of());
        player.setOutputMode(MusicPlayer.OUTPUT_GAME);
        player.setNormalize(false);

        for (var mode : List.of(TrackTransitionMode.GAPLESS, TrackTransitionMode.FADE, TrackTransitionMode.NATURAL, TrackTransitionMode.RHYTHM)) {
            player.setTransitionSeconds(5);
            player.setTransitionTempoMatch(false);
            start(mode,5,6);
            waitFor(() -> id()==6 && player.getLastTransition()!=null, mode+" automatic transition");
            var result=player.getLastTransition();
            check(result.getMode()==mode, mode+" was replaced with "+result.getMode());
            check(outputs.size()==1 && outputs.get(0).drains==0, mode+" restarted the output");
            double boundary=outputs.get(0).getPositionMicros()/1000000.0;
            if (mode==TrackTransitionMode.GAPLESS) {
                check(result.getOverlapMs()==0 && boundary<39.5, "gapless did not trim the silent tail");
                check(player.getPositionMs()>=1150, "gapless did not trim the silent head");
            } else {
                check(result.getOverlapMs()>0 && boundary<39, mode+" did not mix before EOF");
                if (mode==TrackTransitionMode.FADE) {
                    check(result.getOverlapMs()>=4990 && player.getPositionMs()<1000, "fade did not preserve full track boundaries");
                }
            }
            System.out.printf("PASS: %s actual overlap=%d ms, boundary=%.2f s, incoming=%d ms, same output%n",
                mode,result.getOverlapMs(),boundary,player.getPositionMs());
            player.stop();
        }
        start(TrackTransitionMode.FADE,5,6);
        waitFor(()-> requests.containsKey(6),"manual prefetch");
        nap(150);
        player.next();
        waitFor(()->id()==6 && player.getLastTransition()!=null,"manual mix");
        check(player.getLastTransition().getOverlapMs()<=4000 && player.getLastTransition().getOverlapMs()>0,
            "manual transition does not follow original four-second rule");
        check(outputs.size()==1 && outputs.get(0).drains==0,"manual reopened output");
        player.stop();
        System.out.println("PASS: manual transition through real player and original mixer");
    }
}

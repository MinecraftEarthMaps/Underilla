package fr.formiko.mc.underilla.paper.generation;

import java.time.Duration;
import java.util.Locale;
import java.util.function.Consumer;
import org.popcraft.chunky.api.event.task.GenerationProgressEvent;

/** Tracks actual completion; Chunky's separate completion event also fires on pause. */
public final class GenerationProgressListener implements Consumer<GenerationProgressEvent> {
    private final String world;
    private final long interval;
    private final Consumer<String> logger;
    private final Runnable onComplete;
    private long lastPrint;
    private boolean finished;

    public GenerationProgressListener(String world, long interval, Consumer<String> logger, Runnable onComplete) {
        this.world = world;
        this.interval = interval;
        this.logger = logger;
        this.onComplete = onComplete;
        lastPrint = System.currentTimeMillis();
    }

    @Override public synchronized void accept(GenerationProgressEvent event) {
        if (!event.world().equals(world) || finished) return;
        long now = System.currentTimeMillis();
        if (interval > 0 && (event.complete() || now - lastPrint >= interval)) {
            lastPrint = now;
            // Chunky's remaining time uses its current rate, including after a resume.
            String eta = event.complete() ? "PT0S" : event.rate() <= 0 ? "calculating"
                    : Duration.ofHours(event.hours()).plusMinutes(event.minutes()).plusSeconds(event.seconds()).toString();
            logger.accept(String.format(Locale.ROOT, "Task Progress: %d   %.4f%% ETA: %s Rate: %.1f chunks/s, Current: %d %d",
                    event.chunks(), event.progress(), eta, event.rate(), event.x(), event.z()));
        }
        if (event.complete()) {
            finished = true;
            onComplete.run();
        }
    }
}

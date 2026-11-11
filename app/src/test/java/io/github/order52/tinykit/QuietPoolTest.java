package io.github.order52.tinykit;

import static org.junit.Assert.assertEquals;

import java.util.concurrent.ExecutorService;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.AtomicInteger;
import org.junit.Test;

public class QuietPoolTest {
    @Test
    public void workAfterShutdownIsDroppedInsteadOfCrashing() throws Exception {
        ExecutorService pool = MainActivity.quiet(1);
        AtomicInteger ran = new AtomicInteger();
        pool.execute(ran::incrementAndGet);
        pool.shutdown();
        pool.awaitTermination(2, TimeUnit.SECONDS);
        pool.execute(ran::incrementAndGet); // used to throw RejectedExecutionException
        assertEquals(1, ran.get());
    }
}

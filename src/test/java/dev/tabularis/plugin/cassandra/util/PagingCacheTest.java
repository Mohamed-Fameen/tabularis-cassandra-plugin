package dev.tabularis.plugin.cassandra.util;

import org.junit.jupiter.api.Test;

import java.nio.ByteBuffer;

import static org.assertj.core.api.Assertions.assertThat;

class PagingCacheTest {

    private final PagingCache cache = new PagingCache();

    @Test
    void page1NeverNeedsAPagingState() {
        String fp = cache.fingerprint("conn", "SELECT * FROM t", 100);
        assertThat(cache.stateForPage(fp, 1)).isNull();
        assertThat(cache.stateForPage(fp, 0)).isNull();
    }

    @Test
    void stateRememberedAfterAPageIsAvailableForTheNextPage() {
        String fp = cache.fingerprint("conn", "SELECT * FROM t", 100);
        ByteBuffer stateAfterPage1 = ByteBuffer.wrap(new byte[]{1, 2, 3});

        cache.rememberNextPageState(fp, 1, stateAfterPage1);

        assertThat(cache.stateForPage(fp, 2)).isEqualTo(stateAfterPage1);
    }

    @Test
    void cacheMissForAnUnvisitedPageReturnsNull() {
        String fp = cache.fingerprint("conn", "SELECT * FROM t", 100);
        assertThat(cache.stateForPage(fp, 5)).isNull();
    }

    @Test
    void differentFingerprintsDoNotShareState() {
        String fpA = cache.fingerprint("connA", "SELECT * FROM t", 100);
        String fpB = cache.fingerprint("connB", "SELECT * FROM t", 100);
        cache.rememberNextPageState(fpA, 1, ByteBuffer.wrap(new byte[]{9}));

        assertThat(cache.stateForPage(fpB, 2)).isNull();
    }

    @Test
    void differentPageSizesProduceDifferentFingerprints() {
        String fp100 = cache.fingerprint("conn", "SELECT * FROM t", 100);
        String fp50 = cache.fingerprint("conn", "SELECT * FROM t", 50);
        assertThat(fp100).isNotEqualTo(fp50);
    }

    @Test
    void nullNextStateIsNotStored() {
        String fp = cache.fingerprint("conn", "SELECT * FROM t", 100);
        cache.rememberNextPageState(fp, 1, null);
        assertThat(cache.stateForPage(fp, 2)).isNull();
    }
}

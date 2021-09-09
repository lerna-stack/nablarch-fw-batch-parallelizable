package lerna.nablarch.batch.parallelizable.handler;

import static org.hamcrest.MatcherAssert.*;
import static org.hamcrest.Matchers.*;

import org.junit.Test;

public class NullResultTest {
    @Test
    public void NullResultはシングルトンである() {
        NullResult instance1 = NullResult.getInstance();
        NullResult instance2 = NullResult.getInstance();
        assertThat(instance1, is(instance2));
    }

    @Test
    public void ShouldBeFailed() {
        assertThat(1, is(2));
    }

}

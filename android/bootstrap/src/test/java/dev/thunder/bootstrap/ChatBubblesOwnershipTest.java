package dev.thunder.bootstrap;

import static org.junit.Assert.assertFalse;
import static org.junit.Assert.assertSame;
import static org.junit.Assert.assertTrue;

import org.junit.Test;

public final class ChatBubblesOwnershipTest {
    @Test
    public void thunderAppliedValueRestoresItsHostOriginal() {
        ChatBubblesRenderer.FloatValueState state = new ChatBubblesRenderer.FloatValueState();
        float[] value = { 0 };

        state.apply(value[0], 4, next -> value[0] = next);
        assertTrue(state.owned);
        state.restore(value[0], next -> value[0] = next);

        org.junit.Assert.assertEquals(0, value[0], 0);
        assertFalse(state.owned);
    }

    @Test
    public void hostOverwriteIsPreservedDuringRestoration() {
        ChatBubblesRenderer.FloatValueState state = new ChatBubblesRenderer.FloatValueState();
        float[] value = { 0 };

        state.apply(value[0], 4, next -> value[0] = next);
        value[0] = 9;
        state.restore(value[0], next -> value[0] = next);

        org.junit.Assert.assertEquals(9, value[0], 0);
        assertFalse(state.owned);
    }

    @Test
    public void reapplyAfterHostOverwriteCapturesTheNewHostValue() {
        ChatBubblesRenderer.FloatValueState state = new ChatBubblesRenderer.FloatValueState();
        float[] value = { 0 };

        state.apply(value[0], 4, next -> value[0] = next);
        value[0] = 9;
        state.apply(value[0], 4, next -> value[0] = next);
        state.restore(value[0], next -> value[0] = next);

        org.junit.Assert.assertEquals(9, value[0], 0);
        assertFalse(state.owned);
    }

    @Test
    public void layeredThunderValuesKeepTheFirstHostOriginal() {
        ChatBubblesRenderer.FloatValueState state = new ChatBubblesRenderer.FloatValueState();
        float[] value = { 2 };

        state.apply(value[0], 8, next -> value[0] = next);
        state.apply(value[0], 12, next -> value[0] = next);
        state.restore(value[0], next -> value[0] = next);

        org.junit.Assert.assertEquals(2, value[0], 0);
        assertFalse(state.owned);
    }

    @Test
    public void referenceOverwriteIsNotReplacedWithAStaleHostObject() {
        ChatBubblesRenderer.ReferenceValueState<Object> state =
            new ChatBubblesRenderer.ReferenceValueState<>();
        Object original = new Object();
        Object thunder = new Object();
        Object replacement = new Object();
        Object[] value = { original };

        state.apply(value[0], thunder, next -> value[0] = next);
        value[0] = replacement;
        state.restore(value[0], next -> value[0] = next);

        assertSame(replacement, value[0]);
        assertFalse(state.owned);
    }
}

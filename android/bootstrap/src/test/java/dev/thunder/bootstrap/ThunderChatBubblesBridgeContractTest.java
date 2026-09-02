package dev.thunder.bootstrap;

import org.junit.Test;

import java.lang.reflect.Method;
import java.lang.reflect.Modifier;

import static org.junit.Assert.assertEquals;
import static org.junit.Assert.assertTrue;

public final class ThunderChatBubblesBridgeContractTest {
    @Test
    public void exposesCurrentAndAuthenticatedRefreshEntryPoints() throws ReflectiveOperationException {
        assertEntryPoint("thunderBeforeConfigureAccessoriesMargin");
        assertEntryPoint("thunderAfterConfigureAccessoriesMargin");
        assertEntryPoint("thunderBeforeConfigureAuthor");
        assertEntryPoint("thunderAfterConfigureAuthor");
        assertEntryPoint("beforeConfigureAccessoriesMargin");
        assertEntryPoint("afterConfigureAccessoriesMargin");
        assertEntryPoint("beforeConfigureAuthor");
        assertEntryPoint("afterConfigureAuthor");
    }

    private static void assertEntryPoint(String name) throws ReflectiveOperationException {
        Method method = ThunderChatBubblesBridge.class.getDeclaredMethod(name, Object.class);
        assertEquals(void.class, method.getReturnType());
        assertTrue(Modifier.isPublic(method.getModifiers()));
        assertTrue(Modifier.isStatic(method.getModifiers()));
    }
}

package dev.skidfuscator.annotations;

import org.junit.jupiter.api.Test;

import java.lang.annotation.ElementType;
import java.lang.annotation.Target;
import java.util.Arrays;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertTrue;

class NativeObfuscationTest {
    @Test
    void supportsMethodsAndConstructorsWithDefaultMode() throws NoSuchMethodException {
        final Target target = NativeObfuscation.class.getAnnotation(Target.class);
        assertTrue(Arrays.asList(target.value()).contains(ElementType.METHOD));
        assertTrue(Arrays.asList(target.value()).contains(ElementType.CONSTRUCTOR));
        assertEquals(
                NativeObfuscation.Mode.DEFAULT,
                NativeObfuscation.class.getDeclaredMethod("mode").getDefaultValue()
        );
    }
}

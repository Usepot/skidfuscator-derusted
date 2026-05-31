package dev.skidfuscator.testclasses.annotation;

import dev.skidfuscator.testclasses.TestRun;

import java.lang.annotation.ElementType;
import java.lang.annotation.Retention;
import java.lang.annotation.RetentionPolicy;
import java.lang.annotation.Target;

@IntAnnotationTarget.Secret(value = 1337)
public class IntAnnotationTarget implements TestRun {
    @Secret(value = 99)
    public String field;

    @Secret(value = 123)
    public void annotatedMethod() {
    }

    @Override
    public void run() {
        final Secret classAnnotation = IntAnnotationTarget.class.getAnnotation(Secret.class);
        if (classAnnotation.value() != 1337 || classAnnotation.defaulted() != 7331) {
            throw new IllegalStateException("Class annotation int was not decrypted");
        }

        try {
            final Secret fieldAnnotation = IntAnnotationTarget.class
                    .getDeclaredField("field")
                    .getAnnotation(Secret.class);
            if (fieldAnnotation.value() != 99) {
                throw new IllegalStateException("Field annotation int was not decrypted");
            }

            final Secret methodAnnotation = IntAnnotationTarget.class
                    .getDeclaredMethod("annotatedMethod")
                    .getAnnotation(Secret.class);
            if (methodAnnotation.value() != 123) {
                throw new IllegalStateException("Method annotation int was not decrypted");
            }
        } catch (ReflectiveOperationException e) {
            throw new IllegalStateException(e);
        }
    }

    @Retention(RetentionPolicy.RUNTIME)
    @Target({ElementType.TYPE, ElementType.FIELD, ElementType.METHOD, ElementType.TYPE_USE})
    public @interface Secret {
        int value();

        int defaulted() default 7331;

        String label() default "visible";
    }
}

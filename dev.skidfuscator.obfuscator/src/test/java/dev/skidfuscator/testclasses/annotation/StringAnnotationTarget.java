package dev.skidfuscator.testclasses.annotation;

import dev.skidfuscator.testclasses.TestRun;

import java.lang.annotation.ElementType;
import java.lang.annotation.Retention;
import java.lang.annotation.RetentionPolicy;
import java.lang.annotation.Target;
import java.util.Arrays;

@StringAnnotationTarget.Secret(value = "class-secret", tags = {"class-tag-a", "class-tag-b"})
public class StringAnnotationTarget implements TestRun {
    @Secret(value = "field-secret", tags = {"field-tag"})
    public @TypeSecret(value = "field-type-secret", tags = {"field-type-tag"}) String field;

    @Secret(value = "method-secret", tags = {"method-tag"})
    public @TypeSecret(value = "return-type-secret", tags = {"return-type-tag"}) String annotatedMethod() {
        return field;
    }

    @Override
    public void run() {
        final Secret classAnnotation = StringAnnotationTarget.class.getAnnotation(Secret.class);
        assertSecret(classAnnotation, "class-secret", new String[] {"class-tag-a", "class-tag-b"});
        if (!"default-secret".equals(classAnnotation.defaulted())) {
            throw new IllegalStateException("Class annotation default string was not decrypted");
        }

        try {
            assertSecret(
                    StringAnnotationTarget.class.getDeclaredField("field").getAnnotation(Secret.class),
                    "field-secret",
                    new String[] {"field-tag"}
            );
            assertTypeSecret(
                    StringAnnotationTarget.class.getDeclaredField("field").getAnnotatedType().getAnnotation(TypeSecret.class),
                    "field-type-secret",
                    new String[] {"field-type-tag"}
            );
            assertSecret(
                    StringAnnotationTarget.class.getDeclaredMethod("annotatedMethod").getAnnotation(Secret.class),
                    "method-secret",
                    new String[] {"method-tag"}
            );
            assertTypeSecret(
                    StringAnnotationTarget.class.getDeclaredMethod("annotatedMethod").getAnnotatedReturnType().getAnnotation(TypeSecret.class),
                    "return-type-secret",
                    new String[] {"return-type-tag"}
            );
        } catch (ReflectiveOperationException e) {
            throw new IllegalStateException(e);
        }
    }

    private static void assertSecret(final Secret annotation, final String value, final String[] tags) {
        if (annotation == null) {
            throw new IllegalStateException("Missing Secret annotation");
        }
        if (!value.equals(annotation.value())) {
            throw new IllegalStateException("Secret string was not decrypted: " + annotation.value());
        }
        if (!Arrays.equals(tags, annotation.tags())) {
            throw new IllegalStateException("Secret string array was not decrypted: " + Arrays.toString(annotation.tags()));
        }
    }

    private static void assertTypeSecret(final TypeSecret annotation, final String value, final String[] tags) {
        if (annotation == null) {
            throw new IllegalStateException("Missing TypeSecret annotation");
        }
        if (!value.equals(annotation.value())) {
            throw new IllegalStateException("TypeSecret string was not decrypted: " + annotation.value());
        }
        if (!Arrays.equals(tags, annotation.tags())) {
            throw new IllegalStateException("TypeSecret string array was not decrypted: " + Arrays.toString(annotation.tags()));
        }
    }

    @Retention(RetentionPolicy.RUNTIME)
    @Target({ElementType.TYPE, ElementType.FIELD, ElementType.METHOD})
    public @interface Secret {
        String value();

        String[] tags() default {};

        String defaulted() default "default-secret";

        int marker() default 42;
    }

    @Retention(RetentionPolicy.RUNTIME)
    @Target(ElementType.TYPE_USE)
    public @interface TypeSecret {
        String value();

        String[] tags() default {};
    }
}

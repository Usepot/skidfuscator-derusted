package dev.skidfuscator.obfuscator.transform.impl.annotation;

/**
 * Identifies annotations whose element values are consumed <em>structurally</em> —
 * read straight from the class file by an external loader rather than through the
 * obfuscated application's own annotation accessor calls.
 *
 * <p>The annotation-value encryption transformers ({@link StringAnnotationEncryptionTransformer},
 * {@link IntAnnotationEncryptionTransformer}) only restore plaintext at accessor
 * call-sites they rewrite inside transformed application methods. A framework that
 * inspects the annotation directly off the bytecode never hits those call-sites and
 * therefore sees the encrypted constant. For these annotations that is fatal, not
 * cosmetic: e.g. Forge/FML reads {@code @Mod(modid/version/dependencies/...)} from
 * the class bytes during mod discovery, so an encrypted value breaks loading
 * outright. Their values (mod ids, versions, mixin targets, event names) are not
 * secrets, so they are deliberately left in plaintext.</p>
 *
 * <p>Covers Forge/FML ({@code net.minecraftforge.*}), legacy FML ({@code cpw.mods.*})
 * and SpongePowered Mixin ({@code org.spongepowered.asm.mixin.*}).</p>
 */
public final class FrameworkAnnotations {
    private FrameworkAnnotations() {
    }

    /**
     * @param descriptor the annotation type descriptor, e.g. {@code Lnet/minecraftforge/fml/common/Mod;}
     * @return {@code true} if the annotation is read structurally by an external loader
     *         and must keep its element values in plaintext
     */
    public static boolean isStructural(final String descriptor) {
        if (descriptor == null || descriptor.length() < 2 || descriptor.charAt(0) != 'L') {
            return false;
        }

        return descriptor.startsWith("Lnet/minecraftforge/")
                || descriptor.startsWith("Lcpw/mods/")
                || descriptor.startsWith("Lorg/spongepowered/asm/mixin/");
    }
}

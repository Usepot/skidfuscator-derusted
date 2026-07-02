package dev.skidfuscator.obfuscator.transform.impl.misc;

import dev.skidfuscator.config.DefaultTransformerConfig;
import dev.skidfuscator.obfuscator.Skidfuscator;
import dev.skidfuscator.obfuscator.event.EventPriority;
import dev.skidfuscator.obfuscator.event.annotation.Listen;
import dev.skidfuscator.obfuscator.event.impl.transform.skid.FinalSkidTransformEvent;
import dev.skidfuscator.obfuscator.transform.AbstractTransformer;
import dev.skidfuscator.obfuscator.util.MiscUtil;
import org.mapleir.asm.ClassNode;
import org.topdank.byteengineer.commons.data.JarClassData;

public class ProprietaryNoticeTransformer extends AbstractTransformer {
    private static final String MARKER = "[Skidfuscator Proprietary Notice]";

    public ProprietaryNoticeTransformer(final Skidfuscator skidfuscator) {
        super(skidfuscator, "Proprietary Notice");
    }

    @Override
    @SuppressWarnings("unchecked")
    protected <T extends DefaultTransformerConfig> T createConfig() {
        return (T) new ProprietaryNoticeConfig(
                skidfuscator.getTsConfig(),
                MiscUtil.toCamelCase(name)
        );
    }

    @Override
    public ProprietaryNoticeConfig getConfig() {
        return (ProprietaryNoticeConfig) super.getConfig();
    }

    @Listen(EventPriority.MONITOR)
    void handle(final FinalSkidTransformEvent event) {
        final String notice = MARKER + "\n" + getConfig().getMessage();

        for (JarClassData classData : skidfuscator.getJarContents().getClassContents()) {
            final ClassNode classNode = classData.getClassNode();
            if (classNode == null || classNode.node == null) {
                this.skip();
                continue;
            }

            if (skidfuscator.getExemptAnalysis().isExempt(ProprietaryNoticeTransformer.class, classNode)) {
                this.skip();
                continue;
            }

            final String sourceDebug = classNode.node.sourceDebug;
            if (sourceDebug != null && sourceDebug.contains(MARKER)) {
                this.skip();
                continue;
            }

            classNode.node.sourceDebug = sourceDebug == null || sourceDebug.isEmpty()
                    ? notice
                    : sourceDebug + "\n" + notice;

            event.tick();
            this.success();
        }
    }
}

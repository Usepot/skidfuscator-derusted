package dev.skidfuscator.obfuscator.transform.impl.integrity;

import dev.skidfuscator.config.DefaultTransformerConfig;
import dev.skidfuscator.obfuscator.Skidfuscator;
import dev.skidfuscator.obfuscator.event.EventPriority;
import dev.skidfuscator.obfuscator.event.annotation.Listen;
import dev.skidfuscator.obfuscator.event.impl.transform.skid.FinalSkidTransformEvent;
import dev.skidfuscator.obfuscator.transform.AbstractTransformer;
import dev.skidfuscator.obfuscator.util.MiscUtil;

/**
 * Cross-class tamper protection (integrity mesh).
 *
 * <p>The actual checks are materialised at output time in
 * {@link dev.skidfuscator.obfuscator.phantom.jphantom.TamperJarDumper}, where the
 * final remapped bytes of every class exist and can be hashed deterministically
 * and stamped in reverse-topological order. This transformer is the enable gate
 * (off by default), config anchor and SDK dependency for that pass; its listener
 * only reports that the mesh is armed for the run summary.</p>
 *
 * @see dev.skidfuscator.obfuscator.phantom.jphantom.TamperJarDumper
 * @see IntegrityGraph
 */
public class TamperProtectionTransformer extends AbstractTransformer {

    public TamperProtectionTransformer(final Skidfuscator skidfuscator) {
        super(skidfuscator, "Tamper Protection");
        requiresSdk();
    }

    @Override
    @SuppressWarnings("unchecked")
    protected <T extends DefaultTransformerConfig> T createConfig() {
        return (T) new TamperProtectionConfig(skidfuscator.getTsConfig(), MiscUtil.toCamelCase(name));
    }

    @Override
    public TamperProtectionConfig getConfig() {
        return (TamperProtectionConfig) super.getConfig();
    }

    @Listen(EventPriority.MONITOR)
    void handle(final FinalSkidTransformEvent event) {
        Skidfuscator.LOGGER.post(
                "Tamper protection armed (action=" + getConfig().getAction()
                        + "); cross-class integrity checks will be stamped at output."
        );
        this.success();
    }
}

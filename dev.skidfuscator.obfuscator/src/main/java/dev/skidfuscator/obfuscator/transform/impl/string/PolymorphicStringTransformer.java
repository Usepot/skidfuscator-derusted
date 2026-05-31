package dev.skidfuscator.obfuscator.transform.impl.string;

import dev.skidfuscator.obfuscator.Skidfuscator;
import dev.skidfuscator.obfuscator.skidasm.SkidClassNode;
import dev.skidfuscator.obfuscator.transform.impl.string.generator.EncryptionGeneratorV3;
import dev.skidfuscator.obfuscator.transform.impl.string.generator.v3.Base64PaddedV3EncryptionGenerator;
import dev.skidfuscator.obfuscator.transform.impl.string.generator.v3.BytesClinitV3EncryptionGenerator;
import dev.skidfuscator.obfuscator.transform.impl.string.generator.v3.BytesV3EncryptionGenerator;
import dev.skidfuscator.obfuscator.util.RandomUtil;

public class PolymorphicStringTransformer extends StringTransformerV2 {
    public PolymorphicStringTransformer(final Skidfuscator skidfuscator) {
        super(skidfuscator);
    }

    @Override
    protected EncryptionGeneratorV3 createGenerator(final SkidClassNode parentNode) {
        final byte[] pad = createRandomPad();

        switch (RandomUtil.nextInt(3)) {
            case 0:
                return new BytesV3EncryptionGenerator(pad);
            case 1:
                return new BytesClinitV3EncryptionGenerator(pad);
            default:
                return new Base64PaddedV3EncryptionGenerator(pad);
        }
    }
}

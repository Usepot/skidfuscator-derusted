package dev.skidfuscator.nativetoolchain;

import java.io.IOException;
import java.io.InputStream;
import java.net.URI;

/** Opens one bounded release resource without consulting local executable search paths. */
@FunctionalInterface
public interface ToolchainTransport {
    InputStream open(URI uri, long maximumBytes) throws IOException;
}

package elite.intel.bindforge.io;

import java.util.Objects;

/**
 * A {@code .binds} text after {@link BindingsWriter#rewriteDeviceReferences(String, DeviceReferenceRewrite)}, and
 * how many references moved.
 */
public record RewrittenXml(String xml, int references) {

    public RewrittenXml {
        Objects.requireNonNull(xml, "xml");
    }
}

package poc.nav;

import org.w3c.dom.ls.LSInput;
import org.w3c.dom.ls.LSResourceResolver;
import java.io.File;
import java.io.FileInputStream;
import java.io.InputStream;

/** Resolves NAV namespace-only schema imports to local xsd files for JAXP validation. */
public final class LocalXsdResolver implements LSResourceResolver {
    private final File xsdDir;
    public LocalXsdResolver(File xsdDir) { this.xsdDir = xsdDir; }

    @Override
    public LSInput resolveResource(String type, String namespaceURI,
                                   String publicId, String systemId, String baseURI) {
        String file = switch (namespaceURI == null ? "" : namespaceURI) {
            case "http://schemas.nav.gov.hu/NTCA/1.0/common" -> "common.xsd";
            case "http://schemas.nav.gov.hu/OSA/3.0/base"    -> "invoiceBase.xsd";
            case "http://schemas.nav.gov.hu/OSA/3.0/data"    -> "invoiceData.xsd";
            case "http://schemas.nav.gov.hu/OSA/3.0/api"     -> "invoiceApi.xsd";
            default -> null;
        };
        if (file == null) return null;
        return new DomLSInput(new File(xsdDir, file));
    }

    /** Minimal LSInput backed by a file stream. */
    private static final class DomLSInput implements LSInput {
        private final File f;
        DomLSInput(File f) { this.f = f; }
        @Override public InputStream getByteStream() {
            try { return new FileInputStream(f); } catch (Exception e) { throw new RuntimeException(e); }
        }
        @Override public String getSystemId() { return f.toURI().toString(); }
        // Unused LSInput members:
        @Override public java.io.Reader getCharacterStream() { return null; }
        @Override public void setCharacterStream(java.io.Reader r) {}
        @Override public void setByteStream(InputStream b) {}
        @Override public String getStringData() { return null; }
        @Override public void setStringData(String s) {}
        @Override public void setSystemId(String s) {}
        @Override public String getPublicId() { return null; }
        @Override public void setPublicId(String s) {}
        @Override public String getBaseURI() { return null; }
        @Override public void setBaseURI(String s) {}
        @Override public String getEncoding() { return null; }
        @Override public void setEncoding(String e) {}
        @Override public boolean getCertifiedText() { return false; }
        @Override public void setCertifiedText(boolean c) {}
    }
}

package ru.k6lab.server.result;

import javax.imageio.ImageIO;
import java.awt.image.BufferedImage;
import java.io.ByteArrayInputStream;
import java.io.ByteArrayOutputStream;
import java.io.IOException;
import java.nio.charset.StandardCharsets;
import java.util.ArrayList;
import java.util.List;
import java.util.zip.ZipEntry;
import java.util.zip.ZipOutputStream;

/** Минимальный генератор .docx без внешних библиотек: заголовки, абзацы, таблицы и PNG-картинки на альбомной странице A4. */
public final class DocxBuilder {

    private final StringBuilder body = new StringBuilder();
    private final List<byte[]> images = new ArrayList<>();

    private static String esc(String s) {
        if (s == null) {
            return "";
        }
        return s.replace("&", "&amp;").replace("<", "&lt;").replace(">", "&gt;").replaceAll("[\\x00-\\x08\\x0B\\x0C\\x0E-\\x1F]", "");
    }

    public DocxBuilder title(String text) {
        return para("Title", text, false, null);
    }

    public DocxBuilder h1(String text) {
        return para("Heading1", text, false, null);
    }

    public DocxBuilder h2(String text) {
        return para("Heading2", text, false, null);
    }

    public DocxBuilder text(String text) {
        return para(null, text, false, null);
    }

    public DocxBuilder muted(String text) {
        return para(null, text, false, "6C757D");
    }

    public DocxBuilder bold(String text) {
        return para(null, text, true, null);
    }

    private DocxBuilder para(String style, String text, boolean bold, String color) {
        body.append("<w:p>");
        if (style != null) {
            body.append("<w:pPr><w:pStyle w:val=\"").append(style).append("\"/></w:pPr>");
        }
        body.append("<w:r>");
        if (bold || color != null) {
            body.append("<w:rPr>").append(bold ? "<w:b/>" : "").append(color != null ? "<w:color w:val=\"" + color + "\"/>" : "").append("</w:rPr>");
        }
        body.append("<w:t xml:space=\"preserve\">").append(esc(text)).append("</w:t></w:r></w:p>");
        return this;
    }

    /** Таблица: первая строка - заголовок; badRows - индексы строк данных (с 0), подсвечиваемые красным. */
    public DocxBuilder table(List<String> header, List<List<String>> rows, java.util.Set<Integer> badRows) {
        int cols = header.size();
        int width = 13900; // ширина текста A4 альбом при полях 1.5 см, twips
        int cw = width / Math.max(1, cols);
        body.append("<w:tbl><w:tblPr><w:tblW w:w=\"").append(width).append("\" w:type=\"dxa\"/><w:tblBorders>");
        for (String b : new String[]{"top", "left", "bottom", "right", "insideH", "insideV"}) {
            body.append("<w:").append(b).append(" w:val=\"single\" w:sz=\"4\" w:space=\"0\" w:color=\"BFC5CC\"/>");
        }
        body.append("</w:tblBorders><w:tblCellMar><w:left w:w=\"60\" w:type=\"dxa\"/><w:right w:w=\"60\" w:type=\"dxa\"/></w:tblCellMar></w:tblPr><w:tblGrid>");
        for (int i = 0; i < cols; i++) {
            body.append("<w:gridCol w:w=\"").append(cw).append("\"/>");
        }
        body.append("</w:tblGrid>");
        row(header, cw, "1F2937", true, "FFFFFF");
        for (int r = 0; r < rows.size(); r++) {
            row(rows.get(r), cw, badRows != null && badRows.contains(r) ? "F8D7DA" : (r % 2 == 1 ? "F3F4F6" : null), false, null);
        }
        body.append("</w:tbl>");
        return para(null, "", false, null);
    }

    private void row(List<String> cells, int cw, String fill, boolean bold, String color) {
        body.append("<w:tr>");
        for (String c : cells) {
            body.append("<w:tc><w:tcPr><w:tcW w:w=\"").append(cw).append("\" w:type=\"dxa\"/>");
            if (fill != null) {
                body.append("<w:shd w:val=\"clear\" w:color=\"auto\" w:fill=\"").append(fill).append("\"/>");
            }
            body.append("</w:tcPr><w:p><w:pPr><w:spacing w:after=\"0\"/></w:pPr><w:r><w:rPr><w:sz w:val=\"17\"/>")
                    .append(bold ? "<w:b/>" : "").append(color != null ? "<w:color w:val=\"" + color + "\"/>" : "")
                    .append("</w:rPr><w:t xml:space=\"preserve\">").append(esc(c)).append("</w:t></w:r></w:p></w:tc>");
        }
        body.append("</w:tr>");
    }

    /** PNG на всю ширину текста (до 24 см), пропорции сохраняются. */
    public DocxBuilder image(byte[] png, String alt) throws IOException {
        BufferedImage bi = ImageIO.read(new ByteArrayInputStream(png));
        if (bi == null) {
            return this;
        }
        images.add(png);
        int n = images.size();
        long cx = 8_640_000L; // 24 см в EMU
        long cy = cx * bi.getHeight() / bi.getWidth();
        body.append("<w:p><w:pPr><w:keepNext/><w:spacing w:before=\"120\" w:after=\"120\"/></w:pPr><w:r><w:drawing><wp:inline distT=\"0\" distB=\"0\" distL=\"0\" distR=\"0\">")
                .append("<wp:extent cx=\"").append(cx).append("\" cy=\"").append(cy).append("\"/>")
                .append("<wp:docPr id=\"").append(n).append("\" name=\"").append(esc(alt)).append("\" descr=\"").append(esc(alt)).append("\"/>")
                .append("<a:graphic xmlns:a=\"http://schemas.openxmlformats.org/drawingml/2006/main\"><a:graphicData uri=\"http://schemas.openxmlformats.org/drawingml/2006/picture\">")
                .append("<pic:pic xmlns:pic=\"http://schemas.openxmlformats.org/drawingml/2006/picture\"><pic:nvPicPr><pic:cNvPr id=\"").append(n)
                .append("\" name=\"img").append(n).append(".png\"/><pic:cNvPicPr/></pic:nvPicPr><pic:blipFill><a:blip r:embed=\"rIdImg").append(n)
                .append("\"/><a:stretch><a:fillRect/></a:stretch></pic:blipFill><pic:spPr><a:xfrm><a:off x=\"0\" y=\"0\"/><a:ext cx=\"")
                .append(cx).append("\" cy=\"").append(cy).append("\"/></a:xfrm><a:prstGeom prst=\"rect\"><a:avLst/></a:prstGeom></pic:spPr></pic:pic>")
                .append("</a:graphicData></a:graphic></wp:inline></w:drawing></w:r></w:p>");
        return this;
    }

    public byte[] build() throws IOException {
        ByteArrayOutputStream out = new ByteArrayOutputStream();
        try (ZipOutputStream z = new ZipOutputStream(out, StandardCharsets.UTF_8)) {
            put(z, "[Content_Types].xml", "<?xml version=\"1.0\" encoding=\"UTF-8\" standalone=\"yes\"?>"
                    + "<Types xmlns=\"http://schemas.openxmlformats.org/package/2006/content-types\">"
                    + "<Default Extension=\"rels\" ContentType=\"application/vnd.openxmlformats-package.relationships+xml\"/>"
                    + "<Default Extension=\"xml\" ContentType=\"application/xml\"/><Default Extension=\"png\" ContentType=\"image/png\"/>"
                    + "<Override PartName=\"/word/document.xml\" ContentType=\"application/vnd.openxmlformats-officedocument.wordprocessingml.document.main+xml\"/>"
                    + "<Override PartName=\"/word/styles.xml\" ContentType=\"application/vnd.openxmlformats-officedocument.wordprocessingml.styles+xml\"/></Types>");
            put(z, "_rels/.rels", "<?xml version=\"1.0\" encoding=\"UTF-8\" standalone=\"yes\"?>"
                    + "<Relationships xmlns=\"http://schemas.openxmlformats.org/package/2006/relationships\">"
                    + "<Relationship Id=\"rId1\" Type=\"http://schemas.openxmlformats.org/officeDocument/2006/relationships/officeDocument\" Target=\"word/document.xml\"/></Relationships>");
            StringBuilder rels = new StringBuilder("<?xml version=\"1.0\" encoding=\"UTF-8\" standalone=\"yes\"?>"
                    + "<Relationships xmlns=\"http://schemas.openxmlformats.org/package/2006/relationships\">"
                    + "<Relationship Id=\"rIdStyles\" Type=\"http://schemas.openxmlformats.org/officeDocument/2006/relationships/styles\" Target=\"styles.xml\"/>");
            for (int i = 1; i <= images.size(); i++) {
                rels.append("<Relationship Id=\"rIdImg").append(i).append("\" Type=\"http://schemas.openxmlformats.org/officeDocument/2006/relationships/image\" Target=\"media/img")
                        .append(i).append(".png\"/>");
            }
            put(z, "word/_rels/document.xml.rels", rels.append("</Relationships>").toString());
            put(z, "word/styles.xml", STYLES);
            put(z, "word/document.xml", "<?xml version=\"1.0\" encoding=\"UTF-8\" standalone=\"yes\"?>"
                    + "<w:document xmlns:w=\"http://schemas.openxmlformats.org/wordprocessingml/2006/main\" "
                    + "xmlns:r=\"http://schemas.openxmlformats.org/officeDocument/2006/relationships\" "
                    + "xmlns:wp=\"http://schemas.openxmlformats.org/drawingml/2006/wordprocessingDrawing\"><w:body>" + body
                    + "<w:sectPr><w:pgSz w:w=\"16838\" w:h=\"11906\" w:orient=\"landscape\"/>"
                    + "<w:pgMar w:top=\"850\" w:right=\"850\" w:bottom=\"850\" w:left=\"850\" w:header=\"400\" w:footer=\"400\" w:gutter=\"0\"/></w:sectPr></w:body></w:document>");
            for (int i = 0; i < images.size(); i++) {
                z.putNextEntry(new ZipEntry("word/media/img" + (i + 1) + ".png"));
                z.write(images.get(i));
                z.closeEntry();
            }
        }
        return out.toByteArray();
    }

    private static void put(ZipOutputStream z, String name, String content) throws IOException {
        z.putNextEntry(new ZipEntry(name));
        z.write(content.getBytes(StandardCharsets.UTF_8));
        z.closeEntry();
    }

    private static final String STYLES = "<?xml version=\"1.0\" encoding=\"UTF-8\" standalone=\"yes\"?>"
            + "<w:styles xmlns:w=\"http://schemas.openxmlformats.org/wordprocessingml/2006/main\">"
            + "<w:docDefaults><w:rPrDefault><w:rPr><w:rFonts w:ascii=\"Calibri\" w:hAnsi=\"Calibri\" w:cs=\"Calibri\"/><w:sz w:val=\"20\"/><w:lang w:val=\"ru-RU\"/></w:rPr></w:rPrDefault>"
            + "<w:pPrDefault><w:pPr><w:spacing w:after=\"80\"/></w:pPr></w:pPrDefault></w:docDefaults>"
            + "<w:style w:type=\"paragraph\" w:default=\"1\" w:styleId=\"Normal\"><w:name w:val=\"Normal\"/></w:style>"
            + "<w:style w:type=\"paragraph\" w:styleId=\"Title\"><w:name w:val=\"Title\"/><w:basedOn w:val=\"Normal\"/><w:pPr><w:spacing w:after=\"120\"/></w:pPr>"
            + "<w:rPr><w:b/><w:color w:val=\"111827\"/><w:sz w:val=\"44\"/></w:rPr></w:style>"
            + "<w:style w:type=\"paragraph\" w:styleId=\"Heading1\"><w:name w:val=\"heading 1\"/><w:basedOn w:val=\"Normal\"/><w:next w:val=\"Normal\"/><w:pPr><w:keepNext/><w:spacing w:before=\"280\" w:after=\"100\"/><w:outlineLvl w:val=\"0\"/></w:pPr>"
            + "<w:rPr><w:b/><w:color w:val=\"1F2937\"/><w:sz w:val=\"30\"/></w:rPr></w:style>"
            + "<w:style w:type=\"paragraph\" w:styleId=\"Heading2\"><w:name w:val=\"heading 2\"/><w:basedOn w:val=\"Normal\"/><w:next w:val=\"Normal\"/><w:pPr><w:keepNext/><w:spacing w:before=\"200\" w:after=\"80\"/><w:outlineLvl w:val=\"1\"/></w:pPr>"
            + "<w:rPr><w:b/><w:color w:val=\"374151\"/><w:sz w:val=\"24\"/></w:rPr></w:style></w:styles>";
}

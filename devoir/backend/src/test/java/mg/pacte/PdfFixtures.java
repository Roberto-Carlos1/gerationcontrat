package mg.pacte;

import java.io.ByteArrayOutputStream;
import org.apache.pdfbox.pdmodel.*;
import org.apache.pdfbox.pdmodel.encryption.*;
import org.apache.pdfbox.pdmodel.font.*;

final class PdfFixtures {
    static byte[] pdf(String text,boolean encrypted,int pages) throws Exception {
        try(var document=new PDDocument();var bytes=new ByteArrayOutputStream()) {
            for(int i=0;i<pages;i++)document.addPage(new PDPage());
            if(!text.isBlank() && pages>0)try(var content=new PDPageContentStream(document,document.getPage(0))) {
                content.beginText();content.setFont(new PDType1Font(Standard14Fonts.FontName.HELVETICA),12);
                content.newLineAtOffset(40,700);content.showText(text);content.endText();
            }
            if(encrypted)document.protect(new StandardProtectionPolicy("owner-password","reader-password",new AccessPermission()));
            document.save(bytes);return bytes.toByteArray();
        }
    }

    static byte[] scanned() throws Exception {
        var image=new java.awt.image.BufferedImage(1000,400,java.awt.image.BufferedImage.TYPE_INT_RGB);
        var graphics=image.createGraphics();graphics.setColor(java.awt.Color.WHITE);graphics.fillRect(0,0,1000,400);
        graphics.setColor(java.awt.Color.BLACK);graphics.setFont(new java.awt.Font("SansSerif",java.awt.Font.PLAIN,44));
        graphics.drawString("CONTRAT INTERNET",60,100);graphics.drawString("Preavis de 30 jours",60,200);graphics.dispose();
        try(var document=new PDDocument();var bytes=new ByteArrayOutputStream()){
            var page=new PDPage(new org.apache.pdfbox.pdmodel.common.PDRectangle(1000,400));document.addPage(page);
            try(var content=new PDPageContentStream(document,page)){content.drawImage(org.apache.pdfbox.pdmodel.graphics.image.LosslessFactory.createFromImage(document,image),0,0,1000,400);}
            document.save(bytes);return bytes.toByteArray();
        }finally{image.flush();}
    }
}

package edu.pucmm.eict.main.servicios;

import com.google.zxing.BarcodeFormat;
import com.google.zxing.EncodeHintType;
import com.google.zxing.WriterException;
import com.google.zxing.common.BitMatrix;
import com.google.zxing.qrcode.QRCodeWriter;
import com.google.zxing.qrcode.decoder.ErrorCorrectionLevel;

import java.awt.Color;
import java.awt.Graphics2D;
import java.awt.image.BufferedImage;
import java.io.ByteArrayOutputStream;
import java.io.IOException;
import java.util.Base64;
import java.util.HashMap;
import java.util.Map;

import javax.imageio.ImageIO;

public class QRServices {

    private static final int QR_WIDTH = 500;
    private static final int QR_HEIGHT = 500;
    private static final int MARGIN = 4; // Margen blanco grande (quiet zone)

    public static byte[] generarQRBytes(String texto) {
        try {
            QRCodeWriter writer = new QRCodeWriter();

            Map<EncodeHintType, Object> hints = new HashMap<>();
            hints.put(EncodeHintType.CHARACTER_SET, "UTF-8");
            hints.put(EncodeHintType.ERROR_CORRECTION, ErrorCorrectionLevel.M);
            hints.put(EncodeHintType.MARGIN, MARGIN);

            BitMatrix bitMatrix = writer.encode(
                    texto,
                    BarcodeFormat.QR_CODE,
                    QR_WIDTH,
                    QR_HEIGHT,
                    hints
            );

            BufferedImage image = new BufferedImage(QR_WIDTH, QR_HEIGHT, BufferedImage.TYPE_INT_RGB);
            Graphics2D graphics = image.createGraphics();
            graphics.setColor(Color.WHITE);
            graphics.fillRect(0, 0, QR_WIDTH, QR_HEIGHT);
            graphics.setColor(Color.BLACK);

            for (int x = 0; x < QR_WIDTH; x++) {
                for (int y = 0; y < QR_HEIGHT; y++) {
                    if (bitMatrix.get(x, y)) {
                        image.setRGB(x, y, Color.BLACK.getRGB());
                    }
                }
            }
            graphics.dispose();

            ByteArrayOutputStream outputStream = new ByteArrayOutputStream();
            ImageIO.write(image, "PNG", outputStream);
            return outputStream.toByteArray();

        } catch (WriterException | IOException e) {
            throw new RuntimeException("Error al generar el código QR: " + e.getMessage(), e);
        }
    }

public static byte[] generarQRBytesEstructurado(String codigoQr, Long eventoId) {
        // Formato corto: E5:550e8400-e29b-41d4-a716-446655440000
        String contenido = String.format("E%d:%s", eventoId, codigoQr);
        return generarQRBytes(contenido);
    }

    public static class ResultadoQR {
        public Long eventoId;
        public String codigoQr;
        public boolean esEstructurado;

        public ResultadoQR(Long eventoId, String codigoQr, boolean esEstructurado) {
            this.eventoId = eventoId;
            this.codigoQr = codigoQr;
            this.esEstructurado = esEstructurado;
        }
    }

    public static ResultadoQR parsearQREstructurado(String qrLeido) {
        if (qrLeido == null) {
            return new ResultadoQR(null, null, false);
        }

        String contenido = qrLeido.trim();
        if (contenido.isEmpty()) {
            return new ResultadoQR(null, contenido, false);
        }

        Long eventoId = null;
        String codigoQr = contenido;
        boolean esEstructurado = false;

        if (contenido.startsWith("E")) {
            int colonIndex = contenido.indexOf(':');
            if (colonIndex > 1 && colonIndex < contenido.length() - 1) {
                try {
                    String idStr = contenido.substring(1, colonIndex).trim();
                    String codigo = contenido.substring(colonIndex + 1).trim();
                    if (!idStr.isEmpty() && !codigo.isEmpty()) {
                        eventoId = Long.parseLong(idStr);
                        codigoQr = codigo;
                        esEstructurado = true;
                    }
                } catch (NumberFormatException e) {
                    eventoId = null;
                    codigoQr = contenido;
                    esEstructurado = false;
                }
            }
        }

        return new ResultadoQR(eventoId, codigoQr, esEstructurado);
    }

    public static String generarQRBase64(String texto) {
        byte[] qrBytes = generarQRBytes(texto);
        return Base64.getEncoder().encodeToString(qrBytes);
    }

    public static String generarQRBase64Estructurado(String codigoQr, Long eventoId) {
        byte[] qrBytes = generarQRBytesEstructurado(codigoQr, eventoId);
        return Base64.getEncoder().encodeToString(qrBytes);
    }
}
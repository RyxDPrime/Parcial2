package edu.pucmm.eict.main.servicios;

import com.google.zxing.BarcodeFormat;
import com.google.zxing.EncodeHintType;
import com.google.zxing.WriterException;
import com.google.zxing.client.j2se.MatrixToImageWriter;
import com.google.zxing.common.BitMatrix;
import com.google.zxing.qrcode.QRCodeWriter;
import com.google.zxing.qrcode.decoder.ErrorCorrectionLevel;

import java.io.ByteArrayOutputStream;
import java.io.IOException;
import java.util.Base64;
import java.util.HashMap;
import java.util.Map;

public class QRServices {

    private static final int QR_WIDTH = 400;
    private static final int QR_HEIGHT = 400;
    private static final int MARGIN = 3;

    /**
     * Genera QR simple (solo texto)
     */
    public static byte[] generarQRBytes(String texto) {
        try {
            QRCodeWriter writer = new QRCodeWriter();

            Map<EncodeHintType, Object> hints = new HashMap<>();
            hints.put(EncodeHintType.CHARACTER_SET, "UTF-8");
            hints.put(EncodeHintType.ERROR_CORRECTION, ErrorCorrectionLevel.H);
            hints.put(EncodeHintType.MARGIN, MARGIN);

            BitMatrix bitMatrix = writer.encode(
                    texto,
                    BarcodeFormat.QR_CODE,
                    QR_WIDTH,
                    QR_HEIGHT,
                    hints
            );

            ByteArrayOutputStream outputStream = new ByteArrayOutputStream();
            MatrixToImageWriter.writeToStream(bitMatrix, "PNG", outputStream);
            return outputStream.toByteArray();

        } catch (WriterException | IOException e) {
            throw new RuntimeException("Error al generar el código QR: " + e.getMessage(), e);
        }
    }

    /**
     * OPCIÓN B: QR estructurado con EVENTO ID + UUID
     * Formato: EVENTO:{eventoId}|QR:{codigoQr}
     */
    public static byte[] generarQRBytesEstructurado(String codigoQr, Long eventoId) {
        String contenido = String.format("EVENTO:%d|QR:%s", eventoId, codigoQr);
        return generarQRBytes(contenido);
    }

    /**
     * Clase para retornar resultado del parseo
     */
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

    /**
     * Parsea un QR estructurado
     * Retorna ResultadoQR con eventoId y codigoQr
     */
    public static ResultadoQR parsearQREstructurado(String qrLeido) {
        Long eventoId = null;
        String codigoQr = qrLeido;
        boolean esEstructurado = false;

        if (qrLeido != null && qrLeido.startsWith("EVENTO:")) {
            esEstructurado = true;
            String[] partes = qrLeido.split("\\|");
            for (String parte : partes) {
                if (parte.startsWith("EVENTO:")) {
                    try {
                        eventoId = Long.parseLong(parte.substring(7));
                    } catch (NumberFormatException e) {
                        eventoId = null;
                    }
                }
                if (parte.startsWith("QR:")) {
                    codigoQr = parte.substring(3);
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
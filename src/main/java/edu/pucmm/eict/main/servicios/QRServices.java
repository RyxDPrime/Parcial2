package edu.pucmm.eict.main.servicios;

import com.google.zxing.BarcodeFormat;
import com.google.zxing.WriterException;
import com.google.zxing.client.j2se.MatrixToImageWriter;
import com.google.zxing.common.BitMatrix;
import com.google.zxing.qrcode.QRCodeWriter;

import java.io.ByteArrayOutputStream;
import java.io.IOException;
import java.util.Base64;

public class QRServices {

    private static final int QR_WIDTH = 300;
    private static final int QR_HEIGHT = 300;

    public static byte[] generarQRBytes(String texto) {
        try {
            QRCodeWriter writer = new QRCodeWriter();
            BitMatrix bitMatrix = writer.encode(texto, BarcodeFormat.QR_CODE, QR_WIDTH, QR_HEIGHT);

            ByteArrayOutputStream outputStream = new ByteArrayOutputStream();
            MatrixToImageWriter.writeToStream(bitMatrix, "PNG", outputStream);
            return outputStream.toByteArray();

        } catch (WriterException | IOException e) {
            throw new RuntimeException("Error al generar el código QR: " + e.getMessage(), e);
        }
    }

    public static String generarQRBase64(String texto) {
        byte[] qrBytes = generarQRBytes(texto);
        return Base64.getEncoder().encodeToString(qrBytes);
    }
}
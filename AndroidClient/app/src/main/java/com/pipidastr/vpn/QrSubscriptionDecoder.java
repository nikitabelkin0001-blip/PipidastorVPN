package com.pipidastr.vpn;

import android.content.ContentResolver;
import android.graphics.Bitmap;
import android.graphics.BitmapFactory;
import android.graphics.Matrix;
import android.net.Uri;

import com.google.zxing.BarcodeFormat;
import com.google.zxing.BinaryBitmap;
import com.google.zxing.DecodeHintType;
import com.google.zxing.MultiFormatReader;
import com.google.zxing.MultiFormatWriter;
import com.google.zxing.RGBLuminanceSource;
import com.google.zxing.common.BitMatrix;
import com.google.zxing.common.HybridBinarizer;

import java.io.InputStream;
import java.util.Arrays;
import java.util.EnumMap;
import java.util.Map;

final class QrSubscriptionDecoder {
    private QrSubscriptionDecoder() { }

    static Bitmap encode(String value) throws Exception {
        BitMatrix code = new MultiFormatWriter().encode(value, BarcodeFormat.QR_CODE, 900, 900);
        int[] pixels = new int[code.getWidth() * code.getHeight()];
        for (int y = 0; y < code.getHeight(); y++)
            for (int x = 0; x < code.getWidth(); x++)
                pixels[y * code.getWidth() + x] = code.get(x, y) ? 0xff000000 : 0xffffffff;
        return Bitmap.createBitmap(pixels, code.getWidth(), code.getHeight(), Bitmap.Config.ARGB_8888);
    }

    static String decode(ContentResolver resolver, Uri uri) throws Exception {
        BitmapFactory.Options bounds = new BitmapFactory.Options();
        bounds.inJustDecodeBounds = true;
        try (InputStream input = resolver.openInputStream(uri)) {
            if (input == null) throw new IllegalArgumentException("Не удалось открыть изображение.");
            BitmapFactory.decodeStream(input, null, bounds);
        }
        if (bounds.outWidth <= 0 || bounds.outHeight <= 0)
            throw new IllegalArgumentException("Файл не является изображением.");
        BitmapFactory.Options options = new BitmapFactory.Options();
        int largest = Math.max(bounds.outWidth, bounds.outHeight);
        options.inSampleSize = 1;
        while (largest / options.inSampleSize > 2400) options.inSampleSize *= 2;
        Bitmap bitmap;
        try (InputStream input = resolver.openInputStream(uri)) {
            if (input == null) throw new IllegalArgumentException("Не удалось открыть изображение.");
            bitmap = BitmapFactory.decodeStream(input, null, options);
        }
        if (bitmap == null) throw new IllegalArgumentException("Не удалось прочитать изображение.");
        try {
            Map<DecodeHintType, Object> hints = new EnumMap<>(DecodeHintType.class);
            hints.put(DecodeHintType.POSSIBLE_FORMATS, Arrays.asList(BarcodeFormat.QR_CODE));
            hints.put(DecodeHintType.TRY_HARDER, Boolean.TRUE);
            MultiFormatReader reader = new MultiFormatReader();
            for (int turn = 0; turn < 4; turn++) {
                int width = bitmap.getWidth();
                int height = bitmap.getHeight();
                int[] pixels = new int[width * height];
                bitmap.getPixels(pixels, 0, width, 0, 0, width, height);
                try {
                    String text = reader.decode(new BinaryBitmap(new HybridBinarizer(
                            new RGBLuminanceSource(width, height, pixels))), hints).getText().trim();
                    if (text.isEmpty()) throw new IllegalArgumentException("QR-код пуст.");
                    return text;
                } catch (com.google.zxing.ReaderException ignored) { }
                finally { reader.reset(); }
                if (turn < 3) {
                    Matrix matrix = new Matrix();
                    matrix.postRotate(90);
                    Bitmap rotated = Bitmap.createBitmap(bitmap, 0, 0, width, height, matrix, true);
                    bitmap.recycle();
                    bitmap = rotated;
                }
            }
            throw new IllegalArgumentException("QR-код на изображении не найден.");
        } finally {
            bitmap.recycle();
        }
    }
}

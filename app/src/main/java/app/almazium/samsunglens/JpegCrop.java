package app.almazium.samsunglens;

import android.graphics.Bitmap;
import android.graphics.BitmapRegionDecoder;
import android.graphics.Rect;
import android.media.ExifInterface;

import java.io.File;
import java.io.FileInputStream;
import java.io.FileOutputStream;
import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.StandardCopyOption;

final class JpegCrop {
    private static final int MAX_OUTPUT_EDGE = 2048;
    private static final int MAX_INPUT_BYTES = 45 * 1024 * 1024;
    private static final String[] EXIF_TAGS = {
            "Orientation", "Make", "Model", "DateTime", "DateTimeOriginal",
            "DateTimeDigitized", "OffsetTime", "OffsetTimeOriginal", "OffsetTimeDigitized",
            "SubSecTime", "SubSecTimeOriginal", "SubSecTimeDigitized", "ExposureTime",
            "FNumber", "PhotographicSensitivity", "FocalLength", "FocalLengthIn35mmFilm",
            "WhiteBalance", "Flash", "GPSLatitude", "GPSLatitudeRef", "GPSLongitude",
            "GPSLongitudeRef", "GPSAltitude", "GPSAltitudeRef", "GPSDateStamp", "GPSTimeStamp"
    };

    private JpegCrop() {}

    static boolean isHighResolution(int width, int height) {
        return Math.max(width, height) >= 6500 || (long) width * height >= 30_000_000L;
    }

    record Result(int inputWidth, int inputHeight, int width, int height, long inputBytes) {}

    static Result replaceProcessed(File source, float factor) throws IOException {
        long bytes = source.length();
        if (!Float.isFinite(factor) || factor <= 1f || !source.isFile()
                || bytes < 4 || bytes > MAX_INPUT_BYTES) return null;
        File result = File.createTempFile(".almazoom-", ".jpg", source.getParentFile());
        try {
            Result crop = writeCrop(source, factor, result, bytes);
            if (crop != null) {
                Files.move(result.toPath(), source.toPath(),
                        StandardCopyOption.ATOMIC_MOVE, StandardCopyOption.REPLACE_EXISTING);
            }
            return crop;
        } finally {
            Files.deleteIfExists(result.toPath());
        }
    }

    @SuppressWarnings("deprecation")
    private static Result writeCrop(File source, float factor, File result, long bytes) throws IOException {
        try (FileInputStream input = new FileInputStream(source)) {
            if (input.read() != 0xff || input.read() != 0xd8) return null;
        }
        BitmapRegionDecoder decoder = BitmapRegionDecoder.newInstance(source.getAbsolutePath(), false);
        if (decoder == null) return null;
        Bitmap crop;
        int width, height, outW, outH;
        try {
            width = decoder.getWidth();
            height = decoder.getHeight();
            if (isHighResolution(width, height)) return null;
            float resize = Math.min(1f, (float) MAX_OUTPUT_EDGE / Math.max(width, height));
            outW = Math.max(1, Math.round(width * resize));
            outH = Math.max(1, Math.round(height * resize));
            int w = ZoomMath.cropSize(width, factor);
            int h = ZoomMath.cropSize(height, factor);
            int x = (width - w) / 2;
            int y = (height - h) / 2;
            android.graphics.BitmapFactory.Options options = new android.graphics.BitmapFactory.Options();
            options.inSampleSize = 1;
            while (Math.max(w, h) / options.inSampleSize > 2 * MAX_OUTPUT_EDGE) {
                options.inSampleSize *= 2;
            }
            crop = decoder.decodeRegion(new Rect(x, y, x + w, y + h), options);
        } finally {
            decoder.recycle();
        }
        if (crop == null) return null;

        Bitmap scaled = null;
        try {
            scaled = Bitmap.createScaledBitmap(crop, outW, outH, true);
            try (FileOutputStream output = new FileOutputStream(result)) {
                if (!scaled.compress(Bitmap.CompressFormat.JPEG, 94, output)) return null;
            }
            ExifInterface original = new ExifInterface(source.getAbsolutePath());
            ExifInterface outputExif = new ExifInterface(result.getAbsolutePath());
            for (String tag : EXIF_TAGS) {
                String value = original.getAttribute(tag);
                if (value != null) outputExif.setAttribute(tag, value);
            }
            outputExif.setAttribute("ImageWidth", Integer.toString(outW));
            outputExif.setAttribute("ImageLength", Integer.toString(outH));
            outputExif.setAttribute("PixelXDimension", Integer.toString(outW));
            outputExif.setAttribute("PixelYDimension", Integer.toString(outH));
            outputExif.saveAttributes();
            return new Result(width, height, outW, outH, bytes);
        } finally {
            if (scaled != null && scaled != crop) scaled.recycle();
            crop.recycle();
        }
    }

}

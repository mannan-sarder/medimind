package com.mannan.medimind;

import android.content.ContentResolver;
import android.content.Context;
import android.graphics.Bitmap;
import android.graphics.BitmapFactory;
import android.graphics.Matrix;
import android.graphics.Rect;
import android.net.Uri;
import android.util.Log;

import androidx.annotation.NonNull;
import androidx.annotation.Nullable;
import androidx.exifinterface.media.ExifInterface;

import com.google.android.gms.tasks.Task;
import com.google.mlkit.vision.common.InputImage;
import com.google.mlkit.vision.text.Text;
import com.google.mlkit.vision.text.TextRecognition;
import com.google.mlkit.vision.text.TextRecognizer;
import com.google.mlkit.vision.text.latin.TextRecognizerOptions;

import java.io.IOException;
import java.io.InputStream;
import java.util.ArrayList;
import java.util.Collections;
import java.util.Comparator;
import java.util.List;

/**
 * On-device OCR helper — extracts raw text from a scanned report image using
 * ML Kit Text Recognition (Latin script), fully offline.
 *
 * ─────────────────────────────────────────────────────────────────────────────
 * PIPELINE
 * ─────────────────────────────────────────────────────────────────────────────
 *  1. recognizeText(uri, callback) is called from a Fragment with the image
 *     Uri returned by the camera or gallery picker.
 *  2. The image is decoded off the main thread's heavy work where possible,
 *     downscaled to at most OCR_MAX_WIDTH x OCR_MAX_HEIGHT (Constants), and
 *     corrected for EXIF rotation (camera photos are frequently stored
 *     sideways with only an orientation tag set).
 *  3. The corrected Bitmap is wrapped in an InputImage and handed to ML Kit's
 *     TextRecognizer, which runs the on-device model asynchronously.
 *  4. On success, recognised lines (from every text block, regardless of
 *     ML Kit's column/paragraph grouping) are reordered into true visual
 *     rows using their bounding boxes — see buildRawText() — then delivered
 *     via OcrCallback.onSuccess() on the MAIN thread, ready for
 *     ReportParser.parse().
 *  5. On failure (corrupt image, decode error, recognition error), a
 *     user-readable message is delivered via OcrCallback.onFailure().
 *
 * No network access, no API key, no remote service involved at any point.
 */
public class OcrHelper {

    private static final String TAG = "OcrHelper";

    private final Context        appContext;
    private final TextRecognizer recognizer;

    public OcrHelper(Context context) {
        this.appContext = context.getApplicationContext();
        this.recognizer = TextRecognition.getClient(TextRecognizerOptions.DEFAULT_OPTIONS);
    }

    // ═════════════════════════════════════════════════════════════════════
    // Callback contract
    // ═════════════════════════════════════════════════════════════════════

    /** Delivered on the MAIN thread once recognition finishes (success or failure). */
    public interface OcrCallback {
        void onSuccess(String rawText);
        void onFailure(String errorMessage);
    }

    // ═════════════════════════════════════════════════════════════════════
    // Public entry point
    // ═════════════════════════════════════════════════════════════════════

    /**
     * Decodes the image at {@code imageUri}, runs on-device text recognition,
     * and delivers the concatenated raw text via {@code callback}.
     *
     * Safe to call from the main thread — bitmap decoding happens inline
     * (fast for a single downscaled image) and ML Kit's recognizer runs the
     * actual model asynchronously on its own background thread.
     *
     * @param imageUri Content Uri from camera (FileProvider) or gallery picker.
     * @param callback Receives the result on the MAIN thread.
     */
    public void recognizeText(@NonNull Uri imageUri, @NonNull OcrCallback callback) {
        Bitmap bitmap;
        try {
            bitmap = loadAndPrepareBitmap(imageUri);
        } catch (Exception e) {
            Log.e(TAG, "Failed to decode image: " + imageUri, e);
            callback.onFailure(
                    "ছবিটি পড়া যায়নি। অনুগ্রহ করে আরেকটি ছবি বেছে নিন।");
            return;
        }

        if (bitmap == null) {
            callback.onFailure(
                    "ছবিটি পড়া যায়নি। অনুগ্রহ করে আরেকটি ছবি বেছে নিন।");
            return;
        }

        InputImage inputImage;
        try {
            inputImage = InputImage.fromBitmap(bitmap, 0);
        } catch (Exception e) {
            Log.e(TAG, "Failed to wrap bitmap as InputImage", e);
            callback.onFailure(
                    "ছবিটি প্রসেস করতে সমস্যা হয়েছে। আরেকটি ছবি দিয়ে চেষ্টা করুন।");
            return;
        }

        Task<Text> task = recognizer.process(inputImage);
        task.addOnSuccessListener(result -> {
                    String rawText = buildRawText(result);
                    if (rawText.trim().isEmpty()) {
                        callback.onFailure(
                                "ছবিতে কোনো লেখা পাওয়া যায়নি। স্পষ্ট ও সঠিক আলোয় তোলা ছবি দিন।");
                    } else {
                        callback.onSuccess(rawText);
                    }
                })
                .addOnFailureListener(e -> {
                    Log.e(TAG, "Text recognition failed", e);
                    callback.onFailure(
                            "টেক্সট চিহ্নিত করা সম্ভব হয়নি। আরেকটি ছবি দিয়ে চেষ্টা করুন।");
                });
    }

    /** Releases the underlying ML Kit recognizer. Call from Fragment.onDestroyView(). */
    public void close() {
        recognizer.close();
    }

    // ═════════════════════════════════════════════════════════════════════
    // Bitmap loading + downscale + EXIF rotation fix
    // ═════════════════════════════════════════════════════════════════════

    /**
     * Loads the image at {@code uri} downscaled to fit within
     * {@code Constants.OCR_MAX_WIDTH x Constants.OCR_MAX_HEIGHT}, then applies
     * any EXIF rotation so the bitmap is correctly oriented before recognition.
     */
    @Nullable
    private Bitmap loadAndPrepareBitmap(Uri uri) throws IOException {
        ContentResolver resolver = appContext.getContentResolver();

        // Pass 1: decode only bounds to compute a safe downscale factor.
        BitmapFactory.Options boundsOptions = new BitmapFactory.Options();
        boundsOptions.inJustDecodeBounds = true;
        try (InputStream boundsStream = resolver.openInputStream(uri)) {
            if (boundsStream == null) return null;
            BitmapFactory.decodeStream(boundsStream, null, boundsOptions);
        }

        int sampleSize = calculateInSampleSize(
                boundsOptions.outWidth, boundsOptions.outHeight,
                Constants.OCR_MAX_WIDTH, Constants.OCR_MAX_HEIGHT);

        // Pass 2: decode the actual pixels at the computed sample size.
        BitmapFactory.Options decodeOptions = new BitmapFactory.Options();
        decodeOptions.inSampleSize = sampleSize;

        Bitmap rawBitmap;
        try (InputStream dataStream = resolver.openInputStream(uri)) {
            if (dataStream == null) return null;
            rawBitmap = BitmapFactory.decodeStream(dataStream, null, decodeOptions);
        }
        if (rawBitmap == null) return null;

        // Correct EXIF rotation (camera photos are often stored sideways).
        int rotationDegrees = readExifRotationDegrees(uri);
        if (rotationDegrees == 0) {
            return rawBitmap;
        }

        Matrix matrix = new Matrix();
        matrix.postRotate(rotationDegrees);
        Bitmap rotated = Bitmap.createBitmap(
                rawBitmap, 0, 0, rawBitmap.getWidth(), rawBitmap.getHeight(),
                matrix, true);

        if (rotated != rawBitmap) {
            rawBitmap.recycle();
        }
        return rotated;
    }

    /** Computes the smallest power-of-two inSampleSize that fits within the given bounds. */
    private int calculateInSampleSize(int rawWidth, int rawHeight,
                                       int maxWidth, int maxHeight) {
        int sampleSize = 1;
        if (rawWidth <= 0 || rawHeight <= 0) return sampleSize;

        while ((rawWidth / sampleSize) > maxWidth
                || (rawHeight / sampleSize) > maxHeight) {
            sampleSize *= 2;
        }
        return sampleSize;
    }

    /** Reads the EXIF orientation tag from the image and converts it to degrees. */
    private int readExifRotationDegrees(Uri uri) {
        try (InputStream exifStream = appContext.getContentResolver().openInputStream(uri)) {
            if (exifStream == null) return 0;
            ExifInterface exif = new ExifInterface(exifStream);
            int orientation = exif.getAttributeInt(
                    ExifInterface.TAG_ORIENTATION, ExifInterface.ORIENTATION_NORMAL);
            switch (orientation) {
                case ExifInterface.ORIENTATION_ROTATE_90:  return 90;
                case ExifInterface.ORIENTATION_ROTATE_180: return 180;
                case ExifInterface.ORIENTATION_ROTATE_270: return 270;
                default: return 0;
            }
        } catch (IOException e) {
            // EXIF read failure is non-fatal — fall back to no rotation.
            Log.w(TAG, "Could not read EXIF orientation for " + uri, e);
            return 0;
        }
    }

    // ═════════════════════════════════════════════════════════════════════
    // Text assembly
    // ═════════════════════════════════════════════════════════════════════

    /**
     * Reconstructs the true top-to-bottom, left-to-right reading order of a
     * scanned report by clustering ML Kit's recognised {@link Text.Line}s into
     * visual rows using their bounding boxes, then sorting each row left-to-right.
     *
     * WHY THIS IS NEEDED
     * ───────────────────
     * ML Kit's TextRecognizer groups recognised lines into TextBlocks based on
     * paragraph/column structure, NOT strict top-to-bottom row order. For a
     * multi-column lab report table (Test Name | Observation | Unit | Range),
     * this frequently produces one TextBlock per COLUMN — e.g. every test name
     * ends up in one block, every numeric value in a separate block — rather
     * than one block per ROW. The previous implementation simply concatenated
     * block-by-block, which separates a test's name from its value by many
     * lines (or merges it with a neighbouring row's value). That breaks
     * ReportParser's same-line/next-line pairing rule, causing most rows to be
     * silently dropped or paired with the wrong value.
     *
     * This method ignores ML Kit's block grouping entirely and instead:
     *   1. Flattens every Line (across all blocks) into one list.
     *   2. Clusters lines into rows by vertical (Y-axis) bounding-box overlap.
     *   3. Within each row, sorts lines left-to-right by X position.
     *   4. Joins each row's lines with a single space, and rows with '\n'.
     *
     * Net effect: a table row's name, value, unit and range — wherever ML Kit
     * scattered them across its blocks — end up back on the same output line.
     */
    private String buildRawText(Text result) {
        List<Text.Line> allLines = new ArrayList<>();
        for (Text.TextBlock block : result.getTextBlocks()) {
            for (Text.Line line : block.getLines()) {
                String lineText = line.getText();
                if (lineText == null || lineText.trim().isEmpty()) continue;
                allLines.add(line);
            }
        }
        if (allLines.isEmpty()) return "";

        // Lines without a bounding box (rare) can't be spatially ordered —
        // keep them, just append after the spatially-ordered rows.
        List<Text.Line> withBox = new ArrayList<>();
        List<Text.Line> withoutBox = new ArrayList<>();
        for (Text.Line line : allLines) {
            if (line.getBoundingBox() != null) withBox.add(line);
            else withoutBox.add(line);
        }

        // Sort top-to-bottom by the top edge of each line's bounding box.
        Collections.sort(withBox, Comparator.comparingInt(l -> l.getBoundingBox().top));

        // ── Cluster lines into visual rows by vertical overlap ─────────────
        List<List<Text.Line>> rows = new ArrayList<>();
        List<Text.Line> currentRow = new ArrayList<>();
        int rowTop = 0, rowBottom = 0;

        for (Text.Line line : withBox) {
            Rect box = line.getBoundingBox();
            if (currentRow.isEmpty()) {
                currentRow.add(line);
                rowTop = box.top;
                rowBottom = box.bottom;
                continue;
            }

            int overlap = Math.min(box.bottom, rowBottom) - Math.max(box.top, rowTop);
            int lineHeight = box.bottom - box.top;
            int rowHeight = rowBottom - rowTop;
            int smallerHeight = Math.min(lineHeight, rowHeight);
            // Require at least 50% vertical overlap (relative to the shorter
            // of the two) before treating two lines as the same table row.
            boolean sameRow = smallerHeight > 0 && overlap >= 0.5 * smallerHeight;

            if (sameRow) {
                currentRow.add(line);
                rowTop = Math.min(rowTop, box.top);
                rowBottom = Math.max(rowBottom, box.bottom);
            } else {
                rows.add(currentRow);
                currentRow = new ArrayList<>();
                currentRow.add(line);
                rowTop = box.top;
                rowBottom = box.bottom;
            }
        }
        if (!currentRow.isEmpty()) rows.add(currentRow);

        // ── Emit each row left-to-right, rows top-to-bottom ─────────────────
        //
        // A wide horizontal gap between two consecutive lines in the same row
        // (e.g. a left-column "Name : TISHA" sitting at the same Y-position
        // as a right-column "UHID : BD1/556106" in a 2-column report header)
        // is a different kind of boundary than normal word-spacing within one
        // field. Emitting just one space for both makes them visually
        // indistinguishable to downstream regex extraction, which then
        // greedily captures across the column boundary (e.g. patient name
        // "TISHA" + "UHID" merged into one). When the gap is unusually wide
        // relative to the text's own height, we emit a WIDE separator (4
        // spaces) instead of one — a clear, parseable column-boundary signal
        // that ReportParser's name-capture patterns explicitly stop at.
        StringBuilder sb = new StringBuilder();
        for (List<Text.Line> row : rows) {
            Collections.sort(row, Comparator.comparingInt(l -> l.getBoundingBox().left));
            for (int i = 0; i < row.size(); i++) {
                Rect box = row.get(i).getBoundingBox();
                if (i > 0) {
                    Rect prevBox = row.get(i - 1).getBoundingBox();
                    int gap = box.left - prevBox.right;
                    int refHeight = Math.max(1, box.bottom - box.top);
                    sb.append(gap > refHeight * 2 ? "    " : " ");
                }
                sb.append(row.get(i).getText());
            }
            sb.append('\n');
        }
        for (Text.Line line : withoutBox) {
            sb.append(line.getText()).append('\n');
        }
        return sb.toString();
    }
}

package com.example.billbook.util

import android.content.Context
import android.content.Intent
import android.graphics.*
import android.net.Uri
import androidx.core.content.FileProvider
import com.example.billbook.data.BillEntity
import com.example.billbook.data.BillLineItem
import com.google.zxing.BarcodeFormat
import com.google.zxing.qrcode.QRCodeWriter
import java.io.File
import java.io.FileOutputStream
import java.util.Locale

object QrAndReceiptGenerator {

    /**
     * Builds the official NPCI UPI URI with exact bill amount (2 decimal places).
     * When scanned by GPay / PhonePe / Paytm, it displays the exact amount without auto-debiting.
     */
    fun buildUpiDeepLinkUri(upiId: String, shopName: String, exactAmount: Double, billNo: String): String {
        val formattedAmount = String.format(Locale.US, "%.2f", exactAmount)
        return "upi://pay?pa=${upiId.trim()}&pn=${Uri.encode(shopName)}&am=$formattedAmount&cu=INR&tn=${Uri.encode("Bill $billNo")}"
    }

    /**
     * Generates an offline Bitmap QR Code using ZXing (Works in Flight Mode).
     */
    fun generateUpiQrBitmap(upiUri: String, sizePx: Int = 360): Bitmap {
        val matrix = QRCodeWriter().encode(upiUri, BarcodeFormat.QR_CODE, sizePx, sizePx)
        val bmp = Bitmap.createBitmap(sizePx, sizePx, Bitmap.Config.ARGB_8888)
        for (x in 0 until sizePx) {
            for (y in 0 until sizePx) {
                bmp.setPixel(x, y, if (matrix.get(x, y)) Color.BLACK else Color.WHITE)
            }
        }
        return bmp
    }

    /**
     * Renders the complete Shop Bill + Itemized Table + UPI QR Code into a shareable PNG Bitmap.
     */
    fun renderReceiptBitmap(
        shopName: String,
        shopUpiId: String,
        shopPhone: String,
        bill: BillEntity,
        items: List<BillLineItem>
    ): Bitmap {
        val width = 640
        val height = 760 + (items.size * 42)
        val bitmap = Bitmap.createBitmap(width, height, Bitmap.Config.ARGB_8888)
        val canvas = Canvas(bitmap)
        canvas.drawColor(Color.WHITE)

        val paint = Paint(Paint.ANTI_ALIAS_FLAG)
        paint.color = Color.parseColor("#059669")
        canvas.drawRect(0f, 0f, width.toFloat(), 16f, paint)

        paint.color = Color.BLACK
        paint.textSize = 28f
        paint.typeface = Typeface.create(Typeface.DEFAULT, Typeface.BOLD)
        paint.textAlign = Paint.Align.CENTER
        canvas.drawText(shopName, width / 2f, 60f, paint)

        paint.textSize = 18f
        paint.typeface = Typeface.DEFAULT
        canvas.drawText("Ph: $shopPhone | UPI: $shopUpiId", width / 2f, 92f, paint)

        var y = 140f
        paint.textAlign = Paint.Align.LEFT
        paint.typeface = Typeface.MONOSPACE
        canvas.drawText("Bill No: ${bill.billNo}   Date: ${bill.dateKey}", 32f, y, paint)
        y += 30f
        canvas.drawText("Customer: ${bill.customerName}", 32f, y, paint)
        y += 40f

        items.forEachIndexed { idx, item ->
            val amt = String.format(Locale.US, "%.2f", item.price * item.qty)
            canvas.drawText("${idx + 1}. ${item.name.take(22)}  x${item.qty}   Rs.$amt", 32f, y, paint)
            y += 38f
        }

        y += 20f
        canvas.drawText(String.format(Locale.US, "Subtotal: Rs. %.2f", bill.subtotal), 32f, y, paint)
        y += 30f
        canvas.drawText(String.format(Locale.US, "Discount: -Rs. %.2f | GST: +Rs. %.2f", bill.discountAmount, bill.taxAmount), 32f, y, paint)
        y += 42f

        paint.typeface = Typeface.create(Typeface.MONOSPACE, Typeface.BOLD)
        paint.textSize = 24f
        canvas.drawText(String.format(Locale.US, "FINAL PAYABLE: Rs. %.2f", bill.finalAmount), 32f, y, paint)

        // Draw UPI QR
        val upiUri = buildUpiDeepLinkUri(shopUpiId, shopName, bill.finalAmount, bill.billNo)
        val qrBmp = generateUpiQrBitmap(upiUri, 260)
        canvas.drawBitmap(qrBmp, (width - 260) / 2f, y + 30f, null)

        return bitmap
    }

    /**
     * Shares the rendered Bill PNG image directly to WhatsApp or system share sheet.
     */
    fun shareBillAsImage(context: Context, bitmap: Bitmap, billNo: String) {
        val dir = File(context.cacheDir, "shared_bills").apply { mkdirs() }
        val file = File(dir, "Bill_$billNo.png")
        FileOutputStream(file).use { out ->
            bitmap.compress(Bitmap.CompressFormat.PNG, 100, out)
        }
        val uri = FileProvider.getUriForFile(context, "${context.packageName}.fileprovider", file)
        val intent = Intent(Intent.ACTION_SEND).apply {
            type = "image/png"
            putExtra(Intent.EXTRA_STREAM, uri)
            putExtra(Intent.EXTRA_TEXT, "Receipt $billNo — Thank you for shopping with us!")
            addFlags(Intent.FLAG_GRANT_READ_URI_PERMISSION)
        }
        context.startActivity(Intent.createChooser(intent, "Share Bill Image via WhatsApp"))
    }
}
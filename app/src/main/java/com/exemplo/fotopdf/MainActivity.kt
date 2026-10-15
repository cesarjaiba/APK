package com.exemplo.fotopdf

import android.content.ContentValues
import android.graphics.*
import android.graphics.pdf.PdfDocument
import android.net.Uri
import android.os.Bundle
import android.provider.MediaStore
import android.view.Gravity
import android.widget.Button
import android.widget.LinearLayout
import android.widget.TextView
import android.widget.Toast
import androidx.activity.ComponentActivity
import androidx.activity.result.contract.ActivityResultContracts
import androidx.exifinterface.media.ExifInterface
import kotlin.concurrent.thread

class MainActivity : ComponentActivity() {

    private lateinit var status: TextView
    private lateinit var botao: Button

    private val escolher = registerForActivityResult(
        ActivityResultContracts.GetMultipleContents()
    ) { uris ->
        if (uris.isNotEmpty()) gerarPdf(uris)
    }

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        val layout = LinearLayout(this).apply {
            orientation = LinearLayout.VERTICAL
            gravity = Gravity.CENTER
            setPadding(48, 48, 48, 48)
        }
        botao = Button(this).apply {
            text = "Escolher fotos e gerar PDF"
            setOnClickListener { escolher.launch("image/*") }
        }
        status = TextView(this).apply {
            text = "Selecione as fotos na ordem desejada."
            gravity = Gravity.CENTER
            setPadding(0, 32, 0, 0)
        }
        layout.addView(botao)
        layout.addView(status)
        setContentView(layout)
    }

    private fun gerarPdf(uris: List<Uri>) {
        botao.isEnabled = false
        status.text = "Gerando PDF..."
        thread {
            val msg = try {
                val doc = PdfDocument()
                val larg = 595; val alt = 842 // A4
                var n = 0
                for (uri in uris) {
                    val bmp = carregar(uri) ?: continue
                    n++
                    val page = doc.startPage(PdfDocument.PageInfo.Builder(larg, alt, n).create())
                    val escala = minOf(larg.toFloat() / bmp.width, alt.toFloat() / bmp.height)
                    val w = bmp.width * escala; val h = bmp.height * escala
                    val x = (larg - w) / 2; val y = (alt - h) / 2
                    page.canvas.drawBitmap(bmp, null, RectF(x, y, x + w, y + h), Paint(Paint.FILTER_BITMAP_FLAG))
                    doc.finishPage(page)
                    bmp.recycle()
                }
                val valores = ContentValues().apply {
                    put(MediaStore.Downloads.DISPLAY_NAME, "fotos_${System.currentTimeMillis()}.pdf")
                    put(MediaStore.Downloads.MIME_TYPE, "application/pdf")
                    put(MediaStore.Downloads.RELATIVE_PATH, "Download")
                }
                val destino = contentResolver.insert(MediaStore.Downloads.EXTERNAL_CONTENT_URI, valores)!!
                contentResolver.openOutputStream(destino)!!.use { doc.writeTo(it) }
                doc.close()
                "PDF com $n página(s) salvo em Downloads."
            } catch (e: Exception) {
                "Erro: ${e.message}"
            }
            runOnUiThread {
                status.text = msg
                botao.isEnabled = true
                Toast.makeText(this, msg, Toast.LENGTH_LONG).show()
            }
        }
    }

    private fun carregar(uri: Uri): Bitmap? {
        val limites = BitmapFactory.Options().apply { inJustDecodeBounds = true }
        contentResolver.openInputStream(uri)?.use { BitmapFactory.decodeStream(it, null, limites) }
        var amostra = 1
        while (limites.outWidth / amostra > 2000 || limites.outHeight / amostra > 2000) amostra *= 2
        val opts = BitmapFactory.Options().apply { inSampleSize = amostra }
        val bmp = contentResolver.openInputStream(uri)?.use {
            BitmapFactory.decodeStream(it, null, opts)
        } ?: return null
        val orientacao = contentResolver.openInputStream(uri)?.use {
            ExifInterface(it).getAttributeInt(
                ExifInterface.TAG_ORIENTATION, ExifInterface.ORIENTATION_NORMAL
            )
        } ?: ExifInterface.ORIENTATION_NORMAL
        val graus = when (orientacao) {
            ExifInterface.ORIENTATION_ROTATE_90 -> 90f
            ExifInterface.ORIENTATION_ROTATE_180 -> 180f
            ExifInterface.ORIENTATION_ROTATE_270 -> 270f
            else -> 0f
        }
        if (graus == 0f) return bmp
        val girada = Bitmap.createBitmap(bmp, 0, 0, bmp.width, bmp.height, Matrix().apply { postRotate(graus) }, true)
        bmp.recycle()
        return girada
    }
}

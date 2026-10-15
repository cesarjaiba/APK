package com.exemplo.fotopdf

import android.app.AlertDialog
import android.content.ContentValues
import android.graphics.Bitmap
import android.graphics.BitmapFactory
import android.graphics.Matrix
import android.graphics.Paint
import android.graphics.RectF
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
import androidx.core.content.FileProvider
import androidx.exifinterface.media.ExifInterface
import java.io.File
import kotlin.concurrent.thread

class MainActivity : ComponentActivity() {

    private lateinit var status: TextView
    private val botoes = mutableListOf<Button>()

    private val fotosCamera = mutableListOf<Uri>()
    private var uriAtual: Uri? = null

    // ---- Galeria: várias fotos de uma vez ----
    private val escolherGaleria = registerForActivityResult(
        ActivityResultContracts.GetMultipleContents()
    ) { uris ->
        if (uris.isNotEmpty()) gerarPdf(uris)
    }

    // ---- Câmera: uma foto por vez, repetindo até o usuário concluir ----
    private val tirarFoto = registerForActivityResult(
        ActivityResultContracts.TakePicture()
    ) { ok ->
        val uri = uriAtual
        if (ok && uri != null) fotosCamera.add(uri)
        if (fotosCamera.isNotEmpty()) perguntarProxima()
    }

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        val layout = LinearLayout(this).apply {
            orientation = LinearLayout.VERTICAL
            gravity = Gravity.CENTER
            setPadding(48, 48, 48, 48)
        }
        layout.addView(criarBotao("Fotos da galeria") { escolherGaleria.launch("image/*") })
        layout.addView(criarBotao("Fotos da câmera") { fotosCamera.clear(); abrirCamera() })
        status = TextView(this).apply {
            text = "Escolha uma opção para gerar o PDF."
            gravity = Gravity.CENTER
            textSize = 16f
            setPadding(0, 24, 0, 0)
        }
        layout.addView(status)
        setContentView(layout)
    }

    private fun criarBotao(texto: String, acao: () -> Unit): Button {
        val b = Button(this).apply {
            text = texto
            textSize = 18f
            setPadding(32, 40, 32, 40)
            layoutParams = LinearLayout.LayoutParams(
                LinearLayout.LayoutParams.MATCH_PARENT,
                LinearLayout.LayoutParams.WRAP_CONTENT
            ).apply { bottomMargin = 32 }
            setOnClickListener { acao() }
        }
        botoes.add(b)
        return b
    }

    private fun abrirCamera() {
        try {
            val pasta = File(cacheDir, "fotos").apply { mkdirs() }
            val arquivo = File(pasta, "cam_${System.currentTimeMillis()}.jpg")
            val uri = FileProvider.getUriForFile(this, "$packageName.fileprovider", arquivo)
            uriAtual = uri
            tirarFoto.launch(uri)
        } catch (e: Exception) {
            status.text = "Não foi possível abrir a câmera: ${e.message}"
        }
    }

    private fun perguntarProxima() {
        AlertDialog.Builder(this)
            .setTitle("${fotosCamera.size} foto(s) tirada(s)")
            .setMessage("Deseja tirar mais uma foto ou gerar o PDF agora?")
            .setCancelable(false)
            .setPositiveButton("Tirar outra") { _, _ -> abrirCamera() }
            .setNegativeButton("Gerar PDF") { _, _ ->
                val lista = fotosCamera.toList()
                fotosCamera.clear()
                gerarPdf(lista)
            }
            .show()
    }

    private fun gerarPdf(uris: List<Uri>) {
        botoes.forEach { it.isEnabled = false }
        status.text = "Gerando PDF..."
        thread {
            val msg = try {
                val doc = PdfDocument()
                val larg = 595
                val alt = 842 // A4
                var n = 0
                for (uri in uris) {
                    val bmp = carregar(uri) ?: continue
                    n++
                    val page = doc.startPage(PdfDocument.PageInfo.Builder(larg, alt, n).create())
                    val escala = minOf(larg.toFloat() / bmp.width, alt.toFloat() / bmp.height)
                    val w = bmp.width * escala
                    val h = bmp.height * escala
                    val x = (larg - w) / 2
                    val y = (alt - h) / 2
                    page.canvas.drawBitmap(bmp, null, RectF(x, y, x + w, y + h), Paint(Paint.FILTER_BITMAP_FLAG))
                    doc.finishPage(page)
                    bmp.recycle()
                }
                if (n == 0) {
                    doc.close()
                    throw Exception("nenhuma foto pôde ser lida")
                }
                val valores = ContentValues().apply {
                    put(MediaStore.Downloads.DISPLAY_NAME, "fotos_${System.currentTimeMillis()}.pdf")
                    put(MediaStore.Downloads.MIME_TYPE, "application/pdf")
                    put(MediaStore.Downloads.RELATIVE_PATH, "Download")
                }
                val destino = contentResolver.insert(MediaStore.Downloads.EXTERNAL_CONTENT_URI, valores)!!
                contentResolver.openOutputStream(destino)!!.use { doc.writeTo(it) }
                doc.close()
                "PDF com $n página(s) salvo na pasta Downloads."
            } catch (e: Exception) {
                "Erro: ${e.message}"
            } finally {
                File(cacheDir, "fotos").listFiles()?.forEach { it.delete() }
            }
            runOnUiThread {
                status.text = msg
                botoes.forEach { it.isEnabled = true }
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
        val girada = Bitmap.createBitmap(
            bmp, 0, 0, bmp.width, bmp.height,
            Matrix().apply { postRotate(graus) }, true
        )
        bmp.recycle()
        return girada
    }
}

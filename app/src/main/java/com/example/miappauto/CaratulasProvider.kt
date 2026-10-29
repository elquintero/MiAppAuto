package com.example.miappauto

import android.content.ContentProvider
import android.content.ContentValues
import android.database.Cursor
import android.net.Uri
import android.os.ParcelFileDescriptor
import java.io.File
import java.io.FileNotFoundException

/** Entrega los archivos de carátula (solo lectura) a Android Auto. */
class CaratulasProvider : ContentProvider() {

    override fun onCreate(): Boolean = true

    override fun openFile(uri: Uri, mode: String): ParcelFileDescriptor? {
        val nombre = uri.lastPathSegment ?: throw FileNotFoundException()
        if (!Regex("[a-z0-9\\-]+\\.jpg").matches(nombre)) throw FileNotFoundException(nombre)
        val ctx = context ?: throw FileNotFoundException()
        val f = File(Caratulas.carpeta(ctx), nombre)
        if (!f.exists()) throw FileNotFoundException(nombre)
        return ParcelFileDescriptor.open(f, ParcelFileDescriptor.MODE_READ_ONLY)
    }

    override fun getType(uri: Uri): String? = "image/jpeg"

    override fun query(
        uri: Uri, projection: Array<out String>?, selection: String?,
        selectionArgs: Array<out String>?, sortOrder: String?
    ): Cursor? = null

    override fun insert(uri: Uri, values: ContentValues?): Uri? = null

    override fun update(
        uri: Uri, values: ContentValues?, selection: String?, selectionArgs: Array<out String>?
    ): Int = 0

    override fun delete(uri: Uri, selection: String?, selectionArgs: Array<out String>?): Int = 0
}

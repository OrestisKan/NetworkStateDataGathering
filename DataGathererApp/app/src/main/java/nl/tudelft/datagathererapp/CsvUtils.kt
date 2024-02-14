package nl.tudelft.datagathererapp

import android.content.Context
import android.os.Environment
import java.io.File
import java.io.FileWriter
import java.io.IOException

class CsvUtils {

    companion object {
        fun saveToCsv(context: Context, fileName: String, content: String): Boolean {
            return try {
                val downloadsFolder = context.getExternalFilesDir(Environment.DIRECTORY_DOWNLOADS)

                // Create a new file in the Downloads folder
                val file = File(downloadsFolder, fileName)

                // Write the content to the file
                FileWriter(file).use { writer ->
                    writer.append(content)
                }

                true
            } catch (e: IOException) {
                // Handle the exception
                e.printStackTrace()
                false
            }
        }
    }
}

package com.manga.translate

import android.content.Context
import android.net.Uri
import androidx.documentfile.provider.DocumentFile
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import java.io.File

class FreshLibraryImporter(
    context: Context,
    private val repository: LibraryRepository
) {
    private val appContext = context.applicationContext

    suspend fun importFromTree(uri: Uri): ImportOutcome = withContext(Dispatchers.IO) {
        val root = DocumentFile.fromTreeUri(appContext, uri)
            ?: return@withContext ImportOutcome(false, "无法读取所选目录")
        if (!root.canRead()) {
            return@withContext ImportOutcome(false, "所选目录没有读取权限")
        }
        val files = root.listFiles()
        val childFolders = files.filter { file ->
            file.isDirectory && file.listFiles().any { child -> child.isFile && isImageDocument(child) }
        }
        if (childFolders.isNotEmpty()) {
            return@withContext importCollection(root.name ?: "导入合集", childFolders)
        }
        val rootImages = files.filter { it.isFile && isImageDocument(it) }
        if (rootImages.isNotEmpty()) {
            return@withContext importFolder(root.name ?: "导入项目", rootImages)
        }
        ImportOutcome(false, "所选目录内没有可导入图片")
    }

    suspend fun importFromArchiveOrPdf(uri: Uri): ImportOutcome = withContext(Dispatchers.IO) {
        val displayName = runCatching {
            appContext.contentResolver.query(uri, null, null, null, null)?.use { cursor ->
                val index = cursor.getColumnIndex(android.provider.OpenableColumns.DISPLAY_NAME)
                if (index >= 0 && cursor.moveToFirst()) cursor.getString(index) else null
            }
        }.getOrNull().orEmpty()
        val isPdf = displayName.substringAfterLast('.', "").lowercase() == "pdf"
        val result = if (isPdf) repository.importPdf(uri) else repository.importCbz(uri)
        when {
            result == null -> ImportOutcome(false, if (isPdf) "PDF 导入失败" else "压缩包导入失败")
            result.importedCount <= 0 -> ImportOutcome(false, if (isPdf) "PDF 中没有可用图片" else "压缩包中没有可用图片")
            else -> ImportOutcome(true, "已导入 ${result.importedCount} 张图片")
        }
    }

    private fun importFolder(name: String, images: List<DocumentFile>): ImportOutcome {
        val folder = repository.createFolder(name)
            ?: return ImportOutcome(false, "已存在同名项目，请先重命名源目录")
        val added = repository.addImages(folder, images.map { it.uri })
        if (added.isEmpty()) {
            folder.deleteRecursively()
            return ImportOutcome(false, "目录导入失败")
        }
        return ImportOutcome(true, "已导入 ${added.size} 张图片到 ${folder.name}")
    }

    private fun importCollection(name: String, folders: List<DocumentFile>): ImportOutcome {
        val collection = repository.createCollection(name)
            ?: return ImportOutcome(false, "已存在同名合集，请先重命名源目录")
        var importedChapters = 0
        var importedImages = 0
        folders.forEach { source ->
            val chapterName = source.name?.trim().orEmpty()
            if (chapterName.isEmpty()) return@forEach
            val chapterFolder = repository.createChildFolder(collection, chapterName) ?: return@forEach
            val images = source.listFiles().filter { it.isFile && isImageDocument(it) }
            if (images.isEmpty()) {
                chapterFolder.deleteRecursively()
                return@forEach
            }
            val added = repository.addImages(chapterFolder, images.map { it.uri })
            if (added.isEmpty()) {
                chapterFolder.deleteRecursively()
                return@forEach
            }
            importedChapters += 1
            importedImages += added.size
        }
        if (importedChapters <= 0) {
            collection.deleteRecursively()
            return ImportOutcome(false, "合集内没有可导入章节")
        }
        return ImportOutcome(true, "已导入 $importedChapters 个章节，共 $importedImages 张图片")
    }

    private fun isImageDocument(file: DocumentFile): Boolean {
        val name = file.name ?: return false
        return ImageFileSupport.isSupportedSourceImageFileName(name)
    }

    data class ImportOutcome(
        val success: Boolean,
        val message: String
    )
}

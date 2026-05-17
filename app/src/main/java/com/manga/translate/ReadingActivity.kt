package com.manga.translate

import android.content.Context
import android.content.Intent
import android.os.Bundle
import androidx.activity.viewModels
import androidx.appcompat.app.AppCompatActivity
import com.manga.translate.di.appContainer
import java.io.File

class ReadingActivity : AppCompatActivity() {
    private val readingSessionViewModel: ReadingSessionViewModel by viewModels()
    private val repository by lazy(LazyThreadSafetyMode.NONE) { applicationContext.appContainer.libraryRepository }

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        setContentView(R.layout.activity_reading)

        val folderPath = intent.getStringExtra(EXTRA_FOLDER_PATH)
        if (folderPath.isNullOrBlank()) {
            finish()
            return
        }
        val folder = File(folderPath)
        if (!folder.exists()) {
            finish()
            return
        }
        val images = if (repository.isCollectionFolder(folder)) {
            repository.listChildFolders(folder).flatMap { repository.listImages(it) }
        } else {
            repository.listImages(folder)
        }
        if (images.isEmpty()) {
            finish()
            return
        }
        val readingMode = FolderReadingMode.fromPref(intent.getStringExtra(EXTRA_READING_MODE))
        val startIndex = intent.getIntExtra(EXTRA_START_INDEX, 0)
        readingSessionViewModel.setFolder(folder, images, startIndex, readingMode)

        if (savedInstanceState == null) {
            supportFragmentManager.beginTransaction()
                .replace(R.id.reading_activity_container, ReadingFragment())
                .commit()
        }
    }

    companion object {
        private const val EXTRA_FOLDER_PATH = "extra_folder_path"
        private const val EXTRA_START_INDEX = "extra_start_index"
        private const val EXTRA_READING_MODE = "extra_reading_mode"

        fun createIntent(
            context: Context,
            folder: File,
            startIndex: Int,
            readingMode: FolderReadingMode
        ): Intent {
            return Intent(context, ReadingActivity::class.java)
                .putExtra(EXTRA_FOLDER_PATH, folder.absolutePath)
                .putExtra(EXTRA_START_INDEX, startIndex)
                .putExtra(EXTRA_READING_MODE, readingMode.prefValue)
        }
    }
}

package com.manga.translate

import android.content.Intent
import android.net.Uri
import android.os.Bundle
import android.widget.Button
import android.widget.LinearLayout
import android.widget.TextView
import android.widget.Toast
import androidx.activity.result.contract.ActivityResultContracts
import androidx.appcompat.app.AppCompatActivity
import androidx.lifecycle.lifecycleScope
import kotlinx.coroutines.launch
import java.io.File

class FreshMainActivity : AppCompatActivity() {
    private lateinit var statusView: TextView
    private lateinit var emptyView: TextView
    private lateinit var libraryContainer: LinearLayout
    private lateinit var repository: LibraryRepository
    private lateinit var importer: FreshLibraryImporter

    private val importFolderLauncher = registerForActivityResult(
        ActivityResultContracts.OpenDocumentTree()
    ) { uri ->
        uri?.let(::handleImportFolder)
    }

    private val importArchiveLauncher = registerForActivityResult(
        ActivityResultContracts.OpenDocument()
    ) { uri ->
        uri?.let(::handleImportArchive)
    }

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        setContentView(R.layout.activity_fresh_main)

        repository = LibraryRepository(applicationContext)
        importer = FreshLibraryImporter(applicationContext, repository)

        statusView = findViewById(R.id.fresh_home_status)
        emptyView = findViewById(R.id.fresh_library_empty)
        libraryContainer = findViewById(R.id.fresh_library_container)

        findViewById<Button>(R.id.fresh_import_folder_button).setOnClickListener {
            importFolderLauncher.launch(null)
        }
        findViewById<Button>(R.id.fresh_import_archive_button).setOnClickListener {
            importArchiveLauncher.launch(
                arrayOf(
                    "application/vnd.comicbook+zip",
                    "application/x-cbz",
                    "application/zip",
                    "application/pdf"
                )
            )
        }
        findViewById<Button>(R.id.fresh_open_image_task_button).setOnClickListener {
            startActivity(Intent(this, FreshImageTaskActivity::class.java))
        }
        findViewById<Button>(R.id.fresh_open_model_center_button).setOnClickListener {
            startActivity(Intent(this, FreshModelCenterActivity::class.java))
        }
    }

    override fun onResume() {
        super.onResume()
        refreshLibrary()
        statusView.text = getString(R.string.fresh_home_ready)
    }

    private fun handleImportFolder(uri: Uri) {
        try {
            val flags = Intent.FLAG_GRANT_READ_URI_PERMISSION or Intent.FLAG_GRANT_WRITE_URI_PERMISSION
            contentResolver.takePersistableUriPermission(uri, flags)
        } catch (_: SecurityException) {
        }
        statusView.text = getString(R.string.fresh_home_importing)
        lifecycleScope.launch {
            val outcome = importer.importFromTree(uri)
            statusView.text = outcome.message
            Toast.makeText(this@FreshMainActivity, outcome.message, Toast.LENGTH_SHORT).show()
            refreshLibrary()
        }
    }

    private fun handleImportArchive(uri: Uri) {
        statusView.text = getString(R.string.fresh_home_importing)
        lifecycleScope.launch {
            val outcome = importer.importFromArchiveOrPdf(uri)
            statusView.text = outcome.message
            Toast.makeText(this@FreshMainActivity, outcome.message, Toast.LENGTH_SHORT).show()
            refreshLibrary()
        }
    }

    private fun refreshLibrary() {
        val folders = repository.listFolders()
        libraryContainer.removeAllViews()
        emptyView.visibility = if (folders.isEmpty()) android.view.View.VISIBLE else android.view.View.GONE
        folders.forEach { folder ->
            libraryContainer.addView(createFolderButton(folder))
        }
    }

    private fun createFolderButton(folder: File): Button {
        val label = if (repository.isCollectionFolder(folder)) {
            getString(R.string.fresh_collection_item, folder.name)
        } else {
            getString(R.string.fresh_folder_item, folder.name)
        }
        return Button(this).apply {
            text = label
            isAllCaps = false
            setOnClickListener {
                val intent = if (repository.isCollectionFolder(folder)) {
                    FreshFolderBrowserActivity.createIntent(this@FreshMainActivity, folder)
                } else {
                    FreshReaderActivity.createIntent(this@FreshMainActivity, folder)
                }
                startActivity(intent)
            }
        }
    }
}

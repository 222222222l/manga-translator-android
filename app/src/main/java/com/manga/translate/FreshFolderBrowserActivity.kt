package com.manga.translate

import android.content.Context
import android.content.Intent
import android.os.Bundle
import android.widget.Button
import android.widget.LinearLayout
import android.widget.TextView
import androidx.appcompat.app.AppCompatActivity
import java.io.File

class FreshFolderBrowserActivity : AppCompatActivity() {
    private lateinit var repository: LibraryRepository
    private lateinit var titleView: TextView
    private lateinit var statusView: TextView
    private lateinit var container: LinearLayout

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        setContentView(R.layout.activity_fresh_folder_browser)

        repository = LibraryRepository(applicationContext)
        titleView = findViewById(R.id.fresh_browser_title)
        statusView = findViewById(R.id.fresh_browser_status)
        container = findViewById(R.id.fresh_browser_container)

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

        titleView.text = folder.name
        val children = repository.listChildFolders(folder)
        statusView.text = getString(R.string.fresh_browser_status, children.size)
        children.forEach { child ->
            container.addView(Button(this).apply {
                text = child.name
                isAllCaps = false
                setOnClickListener {
                    startActivity(FreshReaderActivity.createIntent(this@FreshFolderBrowserActivity, child))
                }
            })
        }
    }

    companion object {
        private const val EXTRA_FOLDER_PATH = "extra_folder_path"

        fun createIntent(context: Context, folder: File): Intent {
            return Intent(context, FreshFolderBrowserActivity::class.java)
                .putExtra(EXTRA_FOLDER_PATH, folder.absolutePath)
        }
    }
}

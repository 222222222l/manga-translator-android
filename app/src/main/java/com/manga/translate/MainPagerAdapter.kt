package com.manga.translate

import androidx.annotation.StringRes
import androidx.fragment.app.Fragment
import androidx.fragment.app.FragmentActivity
import androidx.viewpager2.adapter.FragmentStateAdapter

class MainPagerAdapter(activity: FragmentActivity) : FragmentStateAdapter(activity) {
    override fun getItemCount(): Int = 3

    override fun createFragment(position: Int): Fragment {
        return when (position) {
            0 -> LibraryFragment()
            1 -> GeneralTaskFragment()
            else -> SettingsHubFragment()
        }
    }

    @StringRes
    fun getTitleRes(position: Int): Int {
        return when (position) {
            0 -> R.string.tab_library
            1 -> R.string.tab_general_task
            else -> R.string.tab_model_center
        }
    }

    companion object {
        const val LIBRARY_INDEX = 0
        const val GENERAL_TASK_INDEX = 1
        const val MODEL_INDEX = 2
        const val SETTINGS_INDEX = MODEL_INDEX
    }
}

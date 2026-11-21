package com.metrolist.music.utils

import android.content.ComponentName
import android.content.Context
import android.content.pm.PackageManager
import androidx.annotation.DrawableRes
import androidx.annotation.StringRes
import com.metrolist.music.R

/** The looks the launcher icon can have. Each one is an activity-alias in the manifest. */
enum class AppIcon(
    val alias: String,
    @StringRes val label: Int,
    @DrawableRes val preview: Int,
) {
    DEFAULT("MainActivityAlias", R.string.auralys_icon_default, R.mipmap.ic_launcher),
    NIGHT("MainActivityNight", R.string.auralys_icon_night, R.mipmap.ic_launcher_night),
    GLASS("MainActivityGlass", R.string.auralys_icon_glass, R.mipmap.ic_launcher_glass),
    GOLD("MainActivityGold", R.string.auralys_icon_gold, R.mipmap.ic_launcher_gold),
    FOREST("MainActivityForest", R.string.auralys_icon_forest, R.mipmap.ic_launcher_forest),
    OCEAN("MainActivityOcean", R.string.auralys_icon_ocean, R.mipmap.ic_launcher_ocean),
    SENJA("MainActivitySenja", R.string.auralys_icon_senja, R.mipmap.ic_launcher_senja),
    PIRINGAN("MainActivityPiringan", R.string.auralys_icon_piringan, R.mipmap.ic_launcher_piringan),
    NEON("MainActivityNeon", R.string.auralys_icon_neon, R.mipmap.ic_launcher_neon),
    GEMA("MainActivityGema", R.string.auralys_icon_gema, R.mipmap.ic_launcher_gema),
    BINTANG("MainActivityBintang", R.string.auralys_icon_bintang, R.mipmap.ic_launcher_bintang),
    AURORA("MainActivityAurora", R.string.auralys_icon_aurora, R.mipmap.ic_launcher_aurora),
    SAKURA("MainActivitySakura", R.string.auralys_icon_sakura, R.mipmap.ic_launcher_sakura),
    MONO("MainActivityMono", R.string.auralys_icon_mono, R.mipmap.ic_launcher_mono),
    KOPI("MainActivityKopi", R.string.auralys_icon_kopi, R.mipmap.ic_launcher_kopi),
    RUBY("MainActivityRuby", R.string.auralys_icon_ruby, R.mipmap.ic_launcher_ruby),
    MATCHA("MainActivityMatcha", R.string.auralys_icon_matcha, R.mipmap.ic_launcher_matcha),
    LAVENDER("MainActivityLavender", R.string.auralys_icon_lavender, R.mipmap.ic_launcher_lavender),
    MEWAH("MainActivityMewah", R.string.auralys_icon_mewah, R.mipmap.ic_launcher_mewah),
    CAIR("MainActivityCair", R.string.auralys_icon_cair, R.mipmap.ic_launcher_cair),
    ;

    companion object {
        fun fromName(name: String?): AppIcon = entries.firstOrNull { it.name == name } ?: DEFAULT
    }
}

object IconUtils {
    private const val STATIC_ALIAS = "MainActivityStatic"

    /**
     * Shows [icon] on the launcher. For [AppIcon.DEFAULT] the older "dynamic colors" choice still decides between the
     * themed alias and the solid-colour one. The new alias is switched on before the others go off, so the app never
     * has a moment without a launcher entry.
     */
    fun apply(context: Context, icon: AppIcon, dynamicColors: Boolean) {
        val target = when {
            icon != AppIcon.DEFAULT -> icon.alias
            dynamicColors -> AppIcon.DEFAULT.alias
            else -> STATIC_ALIAS
        }
        val all = AppIcon.entries.map { it.alias } + STATIC_ALIAS
        setEnabled(context, target, true)
        all.filter { it != target }.forEach { setEnabled(context, it, false) }
    }

    private fun setEnabled(context: Context, alias: String, enabled: Boolean) {
        context.packageManager.setComponentEnabledSetting(
            ComponentName(context, "com.metrolist.music.$alias"),
            if (enabled) PackageManager.COMPONENT_ENABLED_STATE_ENABLED else PackageManager.COMPONENT_ENABLED_STATE_DISABLED,
            PackageManager.DONT_KILL_APP,
        )
    }
}

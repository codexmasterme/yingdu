package io.github.yingdu

import android.content.Context
import android.content.Intent
import android.net.Uri
import android.provider.Settings

/**
 * Nimo 官方 app：翻译、导航这类功能官方做得更完整（用眼镜麦克风 + 云端模型，导航能画小地图和加粗路线），
 * 萤读把连接"交"给官方 app 来用。眼镜同一时间只能连一个 app，所以是切换，不是同时使用。
 */
object OfficialApp {
    private var cached: String? = null

    /** 在桌面 app 里找名字或包名带 nimo 的那个（不是萤读自己）。 */
    fun packageName(ctx: Context): String? {
        cached?.let { return it }
        val pm = ctx.packageManager
        val found = pm.queryIntentActivities(Intent(Intent.ACTION_MAIN).addCategory(Intent.CATEGORY_LAUNCHER), 0)
            .map { it.activityInfo.packageName to it.loadLabel(pm).toString() }
            .filter { it.first != ctx.packageName }
            .firstOrNull { (pkg, label) -> label.contains("nimo", true) || pkg.contains("nimo", true) }
            ?.first
        cached = found
        return found
    }

    fun launch(ctx: Context): Boolean {
        val pkg = packageName(ctx) ?: return false
        val i = ctx.packageManager.getLaunchIntentForPackage(pkg) ?: return false
        ctx.startActivity(i.addFlags(Intent.FLAG_ACTIVITY_NEW_TASK))
        return true
    }

    /** 打开官方 app 的系统设置页，方便"强行停止"后把眼镜连回萤读。 */
    fun openAppSettings(ctx: Context): Boolean {
        val pkg = packageName(ctx) ?: return false
        ctx.startActivity(Intent(Settings.ACTION_APPLICATION_DETAILS_SETTINGS, Uri.parse("package:$pkg"))
            .addFlags(Intent.FLAG_ACTIVITY_NEW_TASK))
        return true
    }
}

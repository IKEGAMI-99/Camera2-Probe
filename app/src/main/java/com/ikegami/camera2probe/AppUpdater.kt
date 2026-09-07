package com.ikegami.camera2probe

import android.app.Activity
import android.app.AlertDialog
import android.content.Intent
import android.net.Uri
import android.os.Build
import android.provider.Settings
import androidx.core.content.FileProvider
import org.json.JSONObject
import java.io.BufferedInputStream
import java.io.File
import java.io.FileOutputStream
import java.net.HttpURLConnection
import java.net.URL

object AppUpdater {
    private const val RELEASE_API = "https://api.github.com/repos/IKEGAMI-99/Camera2-Probe/releases/latest"
    private const val APK_MIME = "application/vnd.android.package-archive"

    fun checkForUpdate(activity: Activity) {
        val progress = AlertDialog.Builder(activity)
            .setTitle("アップデート")
            .setMessage("GitHub の最新版を確認しています…")
            .setCancelable(false)
            .create()
        progress.show()

        Thread {
            try {
                val release = fetchJson(RELEASE_API)
                val assets = release.getJSONArray("assets")
                var apkName: String? = null
                var apkUrl: String? = null
                for (i in 0 until assets.length()) {
                    val asset = assets.getJSONObject(i)
                    val name = asset.optString("name")
                    if (name.endsWith(".apk", ignoreCase = true) && name.startsWith("TRI-CAM-v")) {
                        apkName = name
                        apkUrl = asset.optString("browser_download_url")
                        break
                    }
                }
                val latestName = apkName ?: error("Release APK が見つかりません")
                val latestUrl = apkUrl ?: error("Release URL が見つかりません")
                val latestVersion = Regex("v([0-9]+(?:\\.[0-9]+)*)").find(latestName)?.groupValues?.get(1)
                    ?: error("バージョン番号を取得できません")
                val currentVersion = activity.packageManager.getPackageInfo(activity.packageName, 0).versionName ?: "0"

                activity.runOnUiThread {
                    progress.dismiss()
                    if (compareVersions(latestVersion, currentVersion) <= 0) {
                        AlertDialog.Builder(activity)
                            .setTitle("最新版です")
                            .setMessage("現在 v$currentVersion\n公開版 v$latestVersion")
                            .setPositiveButton("OK", null)
                            .show()
                    } else {
                        AlertDialog.Builder(activity)
                            .setTitle("アップデートがあります")
                            .setMessage("v$currentVersion → v$latestVersion\n\nAPKをダウンロードして更新します。")
                            .setNegativeButton("キャンセル", null)
                            .setPositiveButton("更新") { _, _ -> downloadAndInstall(activity, latestUrl, latestVersion) }
                            .show()
                    }
                }
            } catch (t: Throwable) {
                activity.runOnUiThread {
                    progress.dismiss()
                    AlertDialog.Builder(activity)
                        .setTitle("アップデート確認失敗")
                        .setMessage(t.message ?: t.javaClass.simpleName)
                        .setPositiveButton("OK", null)
                        .show()
                }
            }
        }.start()
    }

    private fun downloadAndInstall(activity: Activity, url: String, version: String) {
        val progress = AlertDialog.Builder(activity)
            .setTitle("v$version をダウンロード")
            .setMessage("APKを取得しています…")
            .setCancelable(false)
            .create()
        progress.show()

        Thread {
            try {
                val dir = File(activity.cacheDir, "updates").apply { mkdirs() }
                val apk = File(dir, "TRI-CAM-v$version.apk")
                val connection = (URL(url).openConnection() as HttpURLConnection).apply {
                    connectTimeout = 15_000
                    readTimeout = 60_000
                    instanceFollowRedirects = true
                    setRequestProperty("User-Agent", "TRI-CAM-Updater")
                }
                connection.connect()
                if (connection.responseCode !in 200..299) error("HTTP ${connection.responseCode}")
                BufferedInputStream(connection.inputStream).use { input ->
                    FileOutputStream(apk).use { output -> input.copyTo(output) }
                }
                connection.disconnect()
                if (apk.length() < 100_000) error("APKサイズが不正です (${apk.length()} bytes)")

                activity.runOnUiThread {
                    progress.dismiss()
                    installApk(activity, apk)
                }
            } catch (t: Throwable) {
                activity.runOnUiThread {
                    progress.dismiss()
                    AlertDialog.Builder(activity)
                        .setTitle("ダウンロード失敗")
                        .setMessage(t.message ?: t.javaClass.simpleName)
                        .setPositiveButton("OK", null)
                        .show()
                }
            }
        }.start()
    }

    private fun installApk(activity: Activity, apk: File) {
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.O && !activity.packageManager.canRequestPackageInstalls()) {
            AlertDialog.Builder(activity)
                .setTitle("インストール許可が必要です")
                .setMessage("このアプリからAPKを更新できるように『不明なアプリのインストール』を許可してください。許可後、設定からもう一度アップデートを実行してください。")
                .setNegativeButton("キャンセル", null)
                .setPositiveButton("設定を開く") { _, _ ->
                    activity.startActivity(
                        Intent(Settings.ACTION_MANAGE_UNKNOWN_APP_SOURCES, Uri.parse("package:${activity.packageName}"))
                    )
                }
                .show()
            return
        }

        val uri = FileProvider.getUriForFile(activity, "${activity.packageName}.fileprovider", apk)
        val intent = Intent(Intent.ACTION_VIEW).apply {
            setDataAndType(uri, APK_MIME)
            addFlags(Intent.FLAG_GRANT_READ_URI_PERMISSION)
            addFlags(Intent.FLAG_ACTIVITY_NEW_TASK)
        }
        activity.startActivity(intent)
    }

    private fun fetchJson(url: String): JSONObject {
        val connection = (URL(url).openConnection() as HttpURLConnection).apply {
            connectTimeout = 15_000
            readTimeout = 20_000
            setRequestProperty("Accept", "application/vnd.github+json")
            setRequestProperty("User-Agent", "TRI-CAM-Updater")
        }
        try {
            val code = connection.responseCode
            if (code !in 200..299) error("GitHub API HTTP $code")
            val text = connection.inputStream.bufferedReader().use { it.readText() }
            return JSONObject(text)
        } finally {
            connection.disconnect()
        }
    }

    private fun compareVersions(a: String, b: String): Int {
        val av = a.split('.').map { it.toIntOrNull() ?: 0 }
        val bv = b.split('.').map { it.toIntOrNull() ?: 0 }
        val n = maxOf(av.size, bv.size)
        for (i in 0 until n) {
            val ai = av.getOrElse(i) { 0 }
            val bi = bv.getOrElse(i) { 0 }
            if (ai != bi) return ai.compareTo(bi)
        }
        return 0
    }
}

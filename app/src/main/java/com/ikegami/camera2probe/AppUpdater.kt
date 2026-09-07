package com.ikegami.camera2probe

import android.app.Activity
import android.app.AlertDialog
import android.content.ClipData
import android.content.Intent
import android.content.pm.PackageManager
import android.net.Uri
import android.os.Build
import android.provider.Settings
import android.widget.Toast
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
    private const val PREFS = "tricam_updater"
    private const val PENDING_PATH = "pending_apk_path"
    private const val PENDING_VERSION = "pending_version"

    fun checkForUpdate(activity: Activity) {
        val progress = AlertDialog.Builder(activity)
            .setTitle("アップデート")
            .setMessage("GitHub Release の最新版を確認しています…")
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
                val currentVersion = currentVersion(activity)

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
                            .setMessage("v$currentVersion → v$latestVersion\n\nAPKをアプリ内で取得してAndroidのインストーラへ渡します。")
                            .setNegativeButton("キャンセル", null)
                            .setPositiveButton("ダウンロード") { _, _ ->
                                downloadAndInstall(activity, latestUrl, latestVersion)
                            }
                            .show()
                    }
                }
            } catch (t: Throwable) {
                activity.runOnUiThread {
                    progress.dismiss()
                    showError(activity, "アップデート確認失敗", t)
                }
            }
        }.start()
    }

    /** Called whenever TRI // CAM comes back to the foreground, including from unknown-source settings. */
    fun resumePendingInstall(activity: Activity) {
        val prefs = activity.getSharedPreferences(PREFS, Activity.MODE_PRIVATE)
        val path = prefs.getString(PENDING_PATH, null) ?: return
        val apk = File(path)
        if (!apk.exists() || apk.length() < 100_000L) {
            prefs.edit().remove(PENDING_PATH).remove(PENDING_VERSION).apply()
            return
        }
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.O && !activity.packageManager.canRequestPackageInstalls()) {
            return
        }
        prefs.edit().remove(PENDING_PATH).remove(PENDING_VERSION).apply()
        activity.window.decorView.postDelayed({ launchInstaller(activity, apk) }, 250L)
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
                val dir = File(activity.filesDir, "updates").apply { mkdirs() }
                dir.listFiles()?.forEach { if (it.isFile && it.name.endsWith(".apk")) it.delete() }
                val apk = File(dir, "TRI-CAM-v$version.apk")
                download(url, apk)
                validatePackage(activity, apk, version)
                rememberPending(activity, apk, version)

                activity.runOnUiThread {
                    progress.dismiss()
                    installOrRequestPermission(activity, apk)
                }
            } catch (t: Throwable) {
                activity.runOnUiThread {
                    progress.dismiss()
                    showError(activity, "ダウンロード / 検証失敗", t)
                }
            }
        }.start()
    }

    private fun download(url: String, apk: File) {
        val connection = (URL(url).openConnection() as HttpURLConnection).apply {
            connectTimeout = 15_000
            readTimeout = 90_000
            instanceFollowRedirects = true
            setRequestProperty("User-Agent", "TRI-CAM-Updater")
            setRequestProperty("Accept", "application/octet-stream")
        }
        try {
            connection.connect()
            if (connection.responseCode !in 200..299) error("HTTP ${connection.responseCode}")
            BufferedInputStream(connection.inputStream).use { input ->
                FileOutputStream(apk).use { output -> input.copyTo(output) }
            }
        } finally {
            connection.disconnect()
        }
        if (apk.length() < 100_000L) error("APKサイズが不正です (${apk.length()} bytes)")
    }

    private fun validatePackage(activity: Activity, apk: File, expectedVersion: String) {
        val pm = activity.packageManager
        val archive = if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.P) {
            pm.getPackageArchiveInfo(apk.absolutePath, PackageManager.GET_SIGNING_CERTIFICATES)
        } else {
            @Suppress("DEPRECATION")
            pm.getPackageArchiveInfo(apk.absolutePath, PackageManager.GET_SIGNATURES)
        } ?: error("ダウンロードしたAPKをAndroidが認識できません")

        if (archive.packageName != activity.packageName) {
            error("パッケージ名が一致しません: ${archive.packageName}")
        }
        val archiveVersion = archive.versionName ?: "?"
        if (archiveVersion != expectedVersion) {
            error("Release表記 v$expectedVersion とAPK v$archiveVersion が一致しません")
        }

        val current = if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.P) {
            pm.getPackageInfo(activity.packageName, PackageManager.GET_SIGNING_CERTIFICATES)
        } else {
            @Suppress("DEPRECATION")
            pm.getPackageInfo(activity.packageName, PackageManager.GET_SIGNATURES)
        }

        val currentCerts = if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.P) {
            current.signingInfo?.apkContentsSigners?.map { it.toByteArray().contentHashCode() }.orEmpty()
        } else {
            @Suppress("DEPRECATION")
            current.signatures?.map { it.toByteArray().contentHashCode() }.orEmpty()
        }
        val newCerts = if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.P) {
            archive.signingInfo?.apkContentsSigners?.map { it.toByteArray().contentHashCode() }.orEmpty()
        } else {
            @Suppress("DEPRECATION")
            archive.signatures?.map { it.toByteArray().contentHashCode() }.orEmpty()
        }
        if (currentCerts.isNotEmpty() && newCerts.isNotEmpty() && currentCerts.toSet() != newCerts.toSet()) {
            error("APK署名が現在のアプリと一致しません。上書き更新できないビルドです")
        }
    }

    private fun installOrRequestPermission(activity: Activity, apk: File) {
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.O && !activity.packageManager.canRequestPackageInstalls()) {
            AlertDialog.Builder(activity)
                .setTitle("1回だけインストール許可が必要です")
                .setMessage("次の画面で『この提供元のアプリを許可』をONにしてください。TRI // CAMへ戻ると、ダウンロード済みAPKのインストーラを自動で開きます。")
                .setNegativeButton("キャンセル", null)
                .setPositiveButton("設定を開く") { _, _ ->
                    activity.startActivity(
                        Intent(
                            Settings.ACTION_MANAGE_UNKNOWN_APP_SOURCES,
                            Uri.parse("package:${activity.packageName}")
                        )
                    )
                }
                .show()
            return
        }
        launchInstaller(activity, apk)
    }

    private fun launchInstaller(activity: Activity, apk: File) {
        try {
            val uri = FileProvider.getUriForFile(activity, "${activity.packageName}.fileprovider", apk)
            val installIntent = Intent(Intent.ACTION_INSTALL_PACKAGE).apply {
                data = uri
                clipData = ClipData.newRawUri("TRI // CAM update", uri)
                addFlags(Intent.FLAG_GRANT_READ_URI_PERMISSION)
                putExtra(Intent.EXTRA_NOT_UNKNOWN_SOURCE, true)
            }
            activity.startActivity(installIntent)
        } catch (first: Throwable) {
            try {
                val uri = FileProvider.getUriForFile(activity, "${activity.packageName}.fileprovider", apk)
                val fallback = Intent(Intent.ACTION_VIEW).apply {
                    setDataAndType(uri, APK_MIME)
                    clipData = ClipData.newRawUri("TRI // CAM update", uri)
                    addFlags(Intent.FLAG_GRANT_READ_URI_PERMISSION)
                }
                activity.startActivity(fallback)
            } catch (second: Throwable) {
                showError(activity, "インストーラを開けません", second)
            }
        }
    }

    private fun rememberPending(activity: Activity, apk: File, version: String) {
        activity.getSharedPreferences(PREFS, Activity.MODE_PRIVATE).edit()
            .putString(PENDING_PATH, apk.absolutePath)
            .putString(PENDING_VERSION, version)
            .apply()
    }

    private fun currentVersion(activity: Activity): String =
        activity.packageManager.getPackageInfo(activity.packageName, 0).versionName ?: "0"

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

    private fun showError(activity: Activity, title: String, t: Throwable) {
        if (activity.isFinishing) {
            Toast.makeText(activity.applicationContext, "$title: ${t.message}", Toast.LENGTH_LONG).show()
            return
        }
        AlertDialog.Builder(activity)
            .setTitle(title)
            .setMessage(t.message ?: t.javaClass.simpleName)
            .setPositiveButton("OK", null)
            .show()
    }
}

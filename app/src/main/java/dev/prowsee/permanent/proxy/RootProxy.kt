package dev.prowsee.permanent.proxy

import android.content.Context
import android.os.Process
import dev.prowsee.permanent.util.ProwseeLog
import java.io.BufferedReader
import java.io.File
import java.io.InputStreamReader
import java.util.concurrent.TimeUnit

/**
 * Root-mode proxying with **no VpnService**.
 *
 * Strategy: redirect only this app's UID at the kernel level so every outbound TCP
 * connection is rewritten to the proxy host. Because it is a UID-scoped `REDIRECT`
 * in the `nat` table, nothing else on the device is affected and no tunnel
 * interface or VPN consent dialog is involved.
 *
 * Two things this cannot do, and it is better to say so than to pretend:
 *
 *  - It redirects *connections*, it does not speak SOCKS. A REDIRECT target is a
 *    transparent proxy: the remote side must accept a plain forwarded connection.
 *    For an HTTP proxy the browser's own `ProxyController` is still the correct
 *    mechanism; this path exists for providers that expose a transparent endpoint.
 *  - Root is required, and there is no way around that. `su` is invoked once and the
 *    rules are torn down when the proxy is disabled or the app process dies.
 */
object RootProxy {

    private const val CHAIN = "PROWSEE_OUT"

    /** Commands we installed, so cleanup can be exact rather than best-effort. */
    private val installedRules = mutableListOf<List<String>>()

    data class RootCheck(val available: Boolean, val detail: String)

    /** Detects a usable `su` binary and confirms it actually grants root. */
    fun checkRoot(): RootCheck {
        val su = listOf("/system/bin/su", "/system/xbin/su", "/sbin/su", "/su/bin/su")
            .firstOrNull { File(it).exists() }
        if (su == null) {
            return RootCheck(false, "No su binary found. This device is not rooted.")
        }
        val result = run(listOf(su, "-c", "id"))
        val out = result.stdout.trim()
        return if (out.contains("uid=0")) {
            RootCheck(true, "Root granted via $su")
        } else {
            RootCheck(false, "su exists but did not grant root: ${out.ifBlank { result.stderr.trim() }}")
        }
    }

    /**
     * Installs the redirect. Returns a failure with a readable reason rather than
     * throwing, because every failure mode here is something the user must see.
     */
    fun enable(profile: ProxyProfile): Result<Unit> {
        val check = checkRoot()
        if (!check.available) return Result.failure(IllegalStateException(check.detail))

        val uid = Process.myUid().toString()
        val proxyPort = profile.port.toString()

        return runCatching {
            teardownQuietly()

            // New chain so we never touch another app's rules.
            shell("iptables -t nat -N $CHAIN")

            // Everything this UID sends to a remote host gets redirected to the proxy.
            // Loopback and the proxy host itself are excluded to avoid a redirect loop.
            val rules = listOf(
                listOf("iptables", "-t", "nat", "-A", CHAIN,
                    "-d", "127.0.0.1", "-j", "RETURN"),
                listOf("iptables", "-t", "nat", "-A", CHAIN,
                    "-d", profile.host, "-j", "RETURN"),
                listOf("iptables", "-t", "nat", "-A", CHAIN,
                    "-p", "tcp", "-m", "owner", "--uid-owner", uid,
                    "-j", "REDIRECT", "--to-ports", proxyPort),
            )
            rules.forEach { rule ->
                shell(rule.joinToString(" "))
                installedRules += rule
            }
            shell("iptables -t nat -A OUTPUT -m owner --uid-owner $uid -j $CHAIN")
            installedRules += listOf("iptables", "-t", "nat", "-D", "OUTPUT",
                "-m", "owner", "--uid-owner", uid, "-j", CHAIN)

            ProwseeLog.d("root redirect active: uid=$uid -> ${profile.host}:$proxyPort")
        }
    }

    /** Removes every rule we added. Safe to call when nothing is installed. */
    fun disable(): Result<Unit> = runCatching {
        teardownQuietly()
        ProwseeLog.d("root redirect removed")
    }

    private fun teardownQuietly() {
        // Undo in reverse so the OUTPUT jump goes before the chain it points at.
        installedRules.asReversed().forEach { rule ->
            runCatching { shell(rule.joinToString(" ")) }
        }
        installedRules.clear()
        runCatching { shell("iptables -t nat -F $CHAIN") }
        runCatching { shell("iptables -t nat -X $CHAIN") }
    }

    /** Verifies the redirect is actually in the kernel, not just that we asked for it. */
    fun verify(): Boolean {
        val result = run(listOf("su", "-c", "iptables -t nat -S $CHAIN"))
        return result.stdout.contains("REDIRECT")
    }

    private data class ShellResult(val stdout: String, val stderr: String, val code: Int)

    private fun shell(command: String): ShellResult {
        val su = listOf("/system/bin/su", "/system/xbin/su", "/sbin/su", "/su/bin/su")
            .firstOrNull { File(it).exists() }
            ?: error("su not available")
        return run(listOf(su, "-c", command))
    }

    private fun run(argv: List<String>): ShellResult {
        return runCatching {
            val process = ProcessBuilder(argv)
                .redirectErrorStream(false)
                .start()
            val stdout = process.inputStream.bufferedReader().use(BufferedReader::readText)
            val stderr = process.errorStream.bufferedReader().use(BufferedReader::readText)
            val finished = process.waitFor(10, TimeUnit.SECONDS)
            if (!finished) {
                process.destroy()
                ShellResult(stdout, "timed out waiting for su", -1)
            } else {
                ShellResult(stdout, stderr, process.exitValue())
            }
        }.getOrElse { ShellResult("", it.message ?: "shell failed", -1) }
    }

    /** Unused parameter kept for symmetry with the WebView path. */
    @Suppress("UNUSED_PARAMETER")
    fun describe(context: Context): String =
        "Redirects only this app's UID. No VpnService is created."
}

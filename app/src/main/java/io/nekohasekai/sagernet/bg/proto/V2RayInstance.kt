/******************************************************************************
 *                                                                            *
 * Copyright (C) 2021 by nekohasekai <contact-sagernet@sekai.icu>             *
 *                                                                            *
 * This program is free software: you can redistribute it and/or modify       *
 * it under the terms of the GNU General Public License as published by       *
 * the Free Software Foundation, either version 3 of the License, or          *
 *  (at your option) any later version.                                       *
 *                                                                            *
 * This program is distributed in the hope that it will be useful,            *
 * but WITHOUT ANY WARRANTY; without even the implied warranty of             *
 * MERCHANTABILITY or FITNESS FOR A PARTICULAR PURPOSE.  See the              *
 * GNU General Public License for more details.                               *
 *                                                                            *
 * You should have received a copy of the GNU General Public License          *
 * along with this program. If not, see <http://www.gnu.org/licenses/>.       *
 *                                                                            *
 ******************************************************************************/

package io.nekohasekai.sagernet.bg.proto

import android.annotation.SuppressLint
import android.os.Build
import android.os.SystemClock
import android.webkit.WebResourceError
import android.webkit.WebResourceRequest
import android.webkit.WebView
import android.webkit.WebViewClient
import go.Seq
import io.nekohasekai.sagernet.RootCAProvider
import io.nekohasekai.sagernet.SagerNet
import io.nekohasekai.sagernet.TrojanProvider
import io.nekohasekai.sagernet.bg.AbstractInstance
import io.nekohasekai.sagernet.bg.ExternalInstance
import io.nekohasekai.sagernet.bg.GuardedProcessPool
import io.nekohasekai.sagernet.database.DataStore
import io.nekohasekai.sagernet.database.ProxyEntity
import io.nekohasekai.sagernet.fmt.LOCALHOST
import io.nekohasekai.sagernet.fmt.V2rayBuildResult
import io.nekohasekai.sagernet.fmt.brook.BrookBean
import io.nekohasekai.sagernet.fmt.brook.internalUri
import io.nekohasekai.sagernet.fmt.brook.toInternalUri
import io.nekohasekai.sagernet.fmt.buildV2RayConfig
import io.nekohasekai.sagernet.fmt.hysteria.HysteriaBean
import io.nekohasekai.sagernet.fmt.hysteria.buildHysteriaConfig
import io.nekohasekai.sagernet.fmt.hysteria2.Hysteria2Bean
import io.nekohasekai.sagernet.fmt.hysteria2.buildHysteria2Config
import io.nekohasekai.sagernet.fmt.internal.ConfigBean
import io.nekohasekai.sagernet.fmt.mieru.MieruBean
import io.nekohasekai.sagernet.fmt.mieru.buildMieruConfig
import io.nekohasekai.sagernet.fmt.mieru2.Mieru2Bean
import io.nekohasekai.sagernet.fmt.mieru2.buildMieru2Config
import io.nekohasekai.sagernet.fmt.naive.NaiveBean
import io.nekohasekai.sagernet.fmt.naive.buildNaiveConfig
import io.nekohasekai.sagernet.fmt.pingtunnel.PingTunnelBean
import io.nekohasekai.sagernet.fmt.relaybaton.RelayBatonBean
import io.nekohasekai.sagernet.fmt.relaybaton.buildRelayBatonConfig
import io.nekohasekai.sagernet.fmt.trojan.TrojanBean
import io.nekohasekai.sagernet.fmt.trojan.buildTrojanConfig
import io.nekohasekai.sagernet.fmt.trojan.buildTrojanGoConfig
import io.nekohasekai.sagernet.fmt.trojan_go.TrojanGoBean
import io.nekohasekai.sagernet.fmt.trojan_go.buildCustomTrojanConfig
import io.nekohasekai.sagernet.fmt.trojan_go.buildTrojanGoConfig
import io.nekohasekai.sagernet.fmt.tuic.TuicBean
import io.nekohasekai.sagernet.fmt.tuic.buildTuicConfig
import io.nekohasekai.sagernet.fmt.tuic5.Tuic5Bean
import io.nekohasekai.sagernet.fmt.tuic5.buildTuic5Config
import io.nekohasekai.sagernet.fmt.shadowtls.ShadowTLSBean
import io.nekohasekai.sagernet.ktx.*
import io.nekohasekai.sagernet.plugin.PluginManager
import kotlinx.coroutines.*
import libcore.V2RayInstance
import java.io.File
import java.util.concurrent.atomic.AtomicBoolean

abstract class V2RayInstance(
    val profile: ProxyEntity
) : AbstractInstance {

    lateinit var config: V2rayBuildResult
    lateinit var v2rayPoint: V2RayInstance
    private lateinit var wsForwarder: WebView

    val pluginPath = hashMapOf<String, PluginManager.InitResult>()
    val pluginConfigs = hashMapOf<Int, Pair<Int, String>>()
    val externalInstances = hashMapOf<Int, AbstractInstance>()
    open lateinit var processes: GuardedProcessPool
    private var cacheFiles = ArrayList<File>()
    var closed by AtomicBoolean()
    fun isInitialized(): Boolean {
        return ::config.isInitialized
    }

    protected fun initPlugin(name: String): PluginManager.InitResult {
        return pluginPath.getOrPut(name) { PluginManager.init(name)!! }
    }

    protected open fun buildConfig() {
        config = buildV2RayConfig(profile)
    }

    protected open fun loadConfig() {
        v2rayPoint.loadConfig(config.config)
    }

    open fun init() {
        v2rayPoint = V2RayInstance()
        buildConfig()
        val enableMux = DataStore.enableMux
        for ((isBalancer, chain) in config.index) {
            chain.entries.forEachIndexed { index, (port, profile) ->
                val needChain = !isBalancer && index != chain.size - 1
                val needMux = enableMux && (isBalancer || index == chain.size - 1)

                when (val bean = profile.requireBean()) {
                    is TrojanBean -> {
                        initPlugin("trojan-go-plugin")
                        pluginConfigs[port] = profile.type to bean.buildTrojanGoConfig(
                            port, needMux
                        )
                    }
                    is TrojanGoBean -> {
                        initPlugin("trojan-go-plugin")
                        pluginConfigs[port] = profile.type to bean.buildTrojanGoConfig(
                            port, needMux
                        )
                    }
                    is NaiveBean -> {
                        initPlugin("naive-plugin")
                        pluginConfigs[port] = profile.type to bean.buildNaiveConfig(port)
                    }
                    is PingTunnelBean -> {
                        if (needChain) error("PingTunnel is incompatible with chain")
                        initPlugin("pingtunnel-plugin")
                    }
                    is RelayBatonBean -> {
                        initPlugin("relaybaton-plugin")
                        pluginConfigs[port] = profile.type to bean.buildRelayBatonConfig(port)
                    }
                    is BrookBean -> {
                        initPlugin("brook-plugin")
                    }
                    is HysteriaBean -> {
                        initPlugin("hysteria-plugin")
                        pluginConfigs[port] = profile.type to bean.buildHysteriaConfig(port) {
                            File(
                                app.noBackupFilesDir,
                                "hysteria_" + SystemClock.elapsedRealtime() + ".ca"
                            ).apply {
                                parentFile?.mkdirs()
                                cacheFiles.add(this)
                            }
                        }
                    }
                    is Hysteria2Bean -> {
                        initPlugin("hysteria2-plugin")
                        pluginConfigs[port] = profile.type to bean.buildHysteria2Config(port) {
                            File(
                                app.noBackupFilesDir,
                                "hysteria2_" + SystemClock.elapsedRealtime() + ".ca"
                            ).apply {
                                parentFile?.mkdirs()
                                cacheFiles.add(this)
                            }
                        }
                    }
                    is MieruBean -> {
                        initPlugin("mieru-plugin")
                        pluginConfigs[port] = profile.type to bean.buildMieruConfig(port)
                    }
                    is Mieru2Bean -> {
                        initPlugin("mieru2-plugin")
                        pluginConfigs[port] = profile.type to bean.buildMieru2Config(port)
                    }
                    is TuicBean -> {
                        initPlugin("tuic-plugin")
                        pluginConfigs[port] = profile.type to bean.buildTuicConfig(port) {
                            File(
                                app.noBackupFilesDir,
                                "tuic_" + SystemClock.elapsedRealtime() + ".ca"
                            ).apply {
                                parentFile?.mkdirs()
                                cacheFiles.add(this)
                            }
                        }
                    }
                    is Tuic5Bean -> {
                        initPlugin("tuic5-plugin")
                        pluginConfigs[port] = profile.type to bean.buildTuic5Config(port) {
                            File(
                                app.noBackupFilesDir,
                                "tuic5_" + SystemClock.elapsedRealtime() + ".ca"
                            ).apply {
                                parentFile?.mkdirs()
                                cacheFiles.add(this)
                            }
                        }
                    }
                    is ShadowTLSBean -> {
                        initPlugin("shadowtls-plugin")
                    }
                    is ConfigBean -> {
                        when (bean.type) {
                            "trojan-go" -> {
                                initPlugin("trojan-go-plugin")
                                pluginConfigs[port] = profile.type to buildCustomTrojanConfig(
                                    bean.content, port
                                )
                            }
                            "v2ray_outbound" -> {
                            }
                            else -> {
                                externalInstances[port] = ExternalInstance(
                                    profile, port
                                ).apply {
                                    init()
                                }
                            }
                        }
                    }
                }
            }
        }
        loadConfig()
    }

    @SuppressLint("SetJavaScriptEnabled")
    override fun launch() {
        val context = if (Build.VERSION.SDK_INT < 24 || SagerNet.user.isUserUnlocked) SagerNet.application else SagerNet.deviceStorage
        val useSystemCACerts = DataStore.providerRootCA == RootCAProvider.SYSTEM
        val rootCaPem by lazy { File(app.filesDir, "mozilla_included.pem").canonicalPath }

        for ((isBalancer, chain) in config.index) {
            chain.entries.forEachIndexed { index, (port, profile) ->
                val bean = profile.requireBean()
                val needChain = !isBalancer && index != chain.size - 1
                val (profileType, config) = pluginConfigs[port] ?: 0 to ""
                val env = mutableMapOf<String, String>()
                if (!useSystemCACerts) {
                    env["SSL_CERT_FILE"] = rootCaPem
                    // disable system directories
                    env["SSL_CERT_DIR"] = "/not_exists"
                }

                when {
                    externalInstances.containsKey(port) -> {
                        externalInstances[port]!!.launch()
                    }
                    bean is TrojanBean -> {
                        val configFile = File(
                            context.noBackupFilesDir,
                            "trojan_" + SystemClock.elapsedRealtime() + ".json"
                        )

                        configFile.parentFile?.mkdirs()
                        configFile.writeText(config)
                        cacheFiles.add(configFile)

                        val commands = listOf(
                            initPlugin("trojan-go-plugin").path, "--config", configFile.absolutePath
                        )

                        processes.start(commands, env)
                    }
                    bean is TrojanGoBean || bean is ConfigBean && bean.type == "trojan-go" -> {
                        val configFile = File(
                            context.noBackupFilesDir,
                            "trojan_go_" + SystemClock.elapsedRealtime() + ".json"
                        )
                        configFile.parentFile?.mkdirs()
                        configFile.writeText(config)
                        cacheFiles.add(configFile)

                        val commands = mutableListOf(
                            initPlugin("trojan-go-plugin").path, "-config", configFile.absolutePath
                        )

                        processes.start(commands, env)
                    }
                    bean is NaiveBean -> {
                        val configFile = File(
                            context.noBackupFilesDir,
                            "naive_" + SystemClock.elapsedRealtime() + ".json"
                        )

                        configFile.parentFile?.mkdirs()
                        configFile.writeText(config)
                        cacheFiles.add(configFile)

                        val commands = mutableListOf(
                            initPlugin("naive-plugin").path, configFile.absolutePath
                        )

                        processes.start(commands, env)
                    }
                    bean is PingTunnelBean -> {
                        if (needChain) error("PingTunnel is incompatible with chain")

                        val commands = mutableListOf(
                            "su",
                            "-c",
                            initPlugin("pingtunnel-plugin").path,
                            "-type",
                            "client",
                            "-sock5",
                            "1",
                            "-l",
                            "$LOCALHOST:$port",
                            "-s",
                            bean.serverAddress
                        )

                        if (bean.key.isNotBlank() && bean.key != "1") {
                            commands.add("-key")
                            commands.add(bean.key)
                        }

                        processes.start(commands, env)
                    }
                    bean is RelayBatonBean -> {
                        val configFile = File(
                            context.noBackupFilesDir,
                            "rb_" + SystemClock.elapsedRealtime() + ".toml"
                        )

                        configFile.parentFile?.mkdirs()
                        configFile.writeText(config)
                        cacheFiles.add(configFile)

                        val commands = mutableListOf(
                            initPlugin("relaybaton-plugin").path,
                            "client",
                            "--config",
                            configFile.absolutePath
                        )

                        processes.start(commands, env)
                    }
                    bean is BrookBean -> {
                        val commands = mutableListOf(initPlugin("brook-plugin").path)

                        when (bean.protocol) {
                            "ws" -> {
                                commands.add("wsclient")
                                commands.add("--wsserver")
                            }
                            "wss" -> {
                                commands.add("wssclient")
                                commands.add("--link") // WTF?
                            }
                            "quic" -> {
                                commands.add("quicclient")
                                commands.add("--quicserver")
                            }
                            else -> {
                                commands.add("client")
                                commands.add("--server")
                            }
                        }

                        if (bean.protocol != "wss") {
                            commands.add(bean.internalUri())
                        } else {
                            commands.add(bean.toInternalUri())
                        }

                        if (bean.protocol == "ws" || bean.protocol == "quic") {
                            commands.add("--address")
                            commands.add(bean.wrapUri())
                            if (bean.withoutBrookProtocol) {
                                commands.add("--withoutBrookProtocol")
                            }
                        }

                        if (bean.protocol == "quic" && bean.insecure) {
                            commands.add("--insecure")
                        }

                        if (bean.protocol != "wss" && bean.udpovertcp) {
                            commands.add("--udpovertcp")
                        }

                        if (bean.protocol != "wss" && bean.password.isNotBlank()) {
                            commands.add("--password")
                            commands.add(bean.password)
                        }

                        commands.add("--socks5")
                        commands.add("$LOCALHOST:$port")

                        processes.start(commands, env)
                    }
                    bean is HysteriaBean -> {
                        val configFile = File(
                            context.noBackupFilesDir,
                            "hysteria_" + SystemClock.elapsedRealtime() + ".json"
                        )

                        configFile.parentFile?.mkdirs()
                        configFile.writeText(config)
                        cacheFiles.add(configFile)

                        val commands = mutableListOf(
                            initPlugin("hysteria-plugin").path,
                            "--no-check",
                            "--config",
                            configFile.absolutePath,
                            "--log-level",
                            if (DataStore.enableLog) "trace" else "warn",
                            "client"
                        )

                        if (bean.protocol == HysteriaBean.PROTOCOL_FAKETCP) {
                            commands.addAll(0, listOf("su", "-c"))
                        }

                        processes.start(commands, env)
                    }
                    bean is Hysteria2Bean -> {
                        val configFile = File(
                            context.noBackupFilesDir,
                            "hysteria2_" + SystemClock.elapsedRealtime() + ".yaml"
                        )

                        configFile.parentFile?.mkdirs()
                        configFile.writeText(config)
                        cacheFiles.add(configFile)

                        val commands = mutableListOf(
                            initPlugin("hysteria2-plugin").path,
                            "--disable-update-check",
                            "--config",
                            configFile.absolutePath,
                            "--log-level",
                            if (DataStore.enableLog) "debug" else "warn",
                            "client"
                        )

                        processes.start(commands, env)
                    }
                    bean is MieruBean -> {
                        val configFile = File(
                            context.noBackupFilesDir,
                            "mieru_" + SystemClock.elapsedRealtime() + ".json"
                        )

                        configFile.parentFile?.mkdirs()
                        configFile.writeText(config)
                        cacheFiles.add(configFile)

                        val plugin = initPlugin("mieru-plugin")

                        env["MIERU_CONFIG_JSON_FILE"] = configFile.absolutePath

                        val commands = mutableListOf(
                            initPlugin("mieru-plugin").path, "run"
                        )

                        processes.start(commands, env)
                    }
                    bean is Mieru2Bean -> {
                        val configFile = File(
                            context.noBackupFilesDir,
                            "mieru2_" + SystemClock.elapsedRealtime() + ".json"
                        )

                        configFile.parentFile?.mkdirs()
                        configFile.writeText(config)
                        cacheFiles.add(configFile)

                        val plugin = initPlugin("mieru2-plugin")

                        env["MIERU2_CONFIG_JSON_FILE"] = configFile.absolutePath

                        val commands = mutableListOf(
                            initPlugin("mieru2-plugin").path, "run"
                        )

                        processes.start(commands, env)
                    }
                    bean is TuicBean -> {
                        val configFile = File(
                            context.noBackupFilesDir,
                            "tuic_" + SystemClock.elapsedRealtime() + ".json"
                        )

                        configFile.parentFile?.mkdirs()
                        configFile.writeText(config)
                        cacheFiles.add(configFile)

                        val commands = mutableListOf(
                            initPlugin("tuic-plugin").path,
                            "-c",
                            configFile.absolutePath,
                        )

                        processes.start(commands, env)
                    }
                    bean is Tuic5Bean -> {
                        val configFile = File(
                            context.noBackupFilesDir,
                            "tuic5_" + SystemClock.elapsedRealtime() + ".json"
                        )

                        configFile.parentFile?.mkdirs()
                        configFile.writeText(config)
                        cacheFiles.add(configFile)

                        val commands = mutableListOf(
                            initPlugin("tuic5-plugin").path,
                            "-c",
                            configFile.absolutePath,
                        )

                        processes.start(commands, env)
                    }
                    bean is ShadowTLSBean -> {
                        val commands = mutableListOf(initPlugin("shadowtls-plugin").path)
                        if (bean.v3) {
                            commands.add("--v3")
                        }
                        commands.add("client")
                        commands.add("--listen")
                        commands.add("$LOCALHOST:$port")
                        commands.add("--server")
                        commands.add(bean.wrapUri())
                        if (bean.sni.isNotBlank()) {
                            commands.add("--sni")
                            commands.add(bean.sni)
                        }
                        if (bean.alpn.isNotBlank()) {
                            commands.add("--alpn")
                            commands.add(bean.alpn)
                        }
                        if (bean.password.isNotBlank()) {
                            commands.add("--password")
                            commands.add(bean.password)
                        }
                        processes.start(commands, env)
                    }
                }
            }
        }

        v2rayPoint.start()

        if (config.requireWs) {
            val url = "http://$LOCALHOST:" + (config.wsPort) + "/"

            runOnMainDispatcher {
                wsForwarder = WebView(context)
                wsForwarder.settings.javaScriptEnabled = true
                wsForwarder.webViewClient = object : WebViewClient() {
                    override fun onReceivedError(
                        view: WebView?,
                        request: WebResourceRequest?,
                        error: WebResourceError?,
                    ) {
                        Logs.d("WebView load r: $error")

                        runOnMainDispatcher {
                            wsForwarder.loadUrl("about:blank")

                            delay(1000L)
                            wsForwarder.loadUrl(url)
                        }
                    }

                    override fun onPageFinished(view: WebView, url: String) {
                        super.onPageFinished(view, url)

                        Logs.d("WebView loaded: ${view.title}")

                    }
                }
                wsForwarder.loadUrl(url)
            }
        }

    }

    private var isClosed = false

    @Suppress("EXPERIMENTAL_API_USAGE")
    override fun close() {
        if (isClosed) return

        for (instance in externalInstances.values) {
            runCatching {
                instance.close()
            }
        }

        cacheFiles.removeAll { it.delete(); true }

        if (::wsForwarder.isInitialized) {
            runBlocking {
                onMainDispatcher {
                    wsForwarder.loadUrl("about:blank")
                    wsForwarder.destroy()
                }
            }
        }

        if (::processes.isInitialized) processes.close(GlobalScope + Dispatchers.IO)

        if (::v2rayPoint.isInitialized) {
            v2rayPoint.close()
        }

        isClosed = true
    }

}

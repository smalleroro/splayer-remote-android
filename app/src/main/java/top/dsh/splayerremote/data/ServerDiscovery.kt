package top.dsh.splayerremote.data

import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.async
import kotlinx.coroutines.awaitAll
import kotlinx.coroutines.coroutineScope
import kotlinx.coroutines.withContext
import java.net.Inet4Address
import java.net.InetSocketAddress
import java.net.NetworkInterface
import java.net.Socket

/**
 * 局域网内自动发现 SPlayer 外部 API。
 *
 * 用户装好 App 后不该被"服务器地址"卡住——让他去电脑上查 IP 再手输，
 * 这是最烦的一步。这里直接扫本机所在 /24 网段，
 * 谁在 14558 上回了 SPlayer 的 /api/info，就是它。
 *
 * 代价：一次最多 254 个 TCP 连接尝试，并发 64、超时 250ms，
 * 最坏情况 1 秒左右收敛。只在用户点"自动发现"时才跑。
 */
object ServerDiscovery {

    private const val PORT = 14558
    private const val CONCURRENCY = 64
    private const val CONNECT_TIMEOUT_MS = 250

    /** 探测结果：命中的 http 基址，找不到返回 null */
    suspend fun discover(port: Int = PORT): String? = withContext(Dispatchers.IO) {
        val localIp = findLocalIpv4() ?: return@withContext null
        val prefix = localIp.substringBeforeLast('.')
        val candidates = (1..254).map { "$prefix.$it" }

        coroutineScope {
            candidates.chunked(CONCURRENCY).firstNotNullOfOrNull { batch ->
                batch
                    .map { host ->
                        async(Dispatchers.IO) { if (probe(host, port)) "http://$host:$port" else null }
                    }
                    .awaitAll()
                    .firstOrNull { it != null }
            }
        }
    }

    /** TCP 通了还要确认对面真是 SPlayer，避免误判成别的服务 */
    private fun probe(host: String, port: Int): Boolean {
        return try {
            Socket().use { socket ->
                socket.connect(InetSocketAddress(host, port), CONNECT_TIMEOUT_MS)
                socket.soTimeout = CONNECT_TIMEOUT_MS
                // 直接问 /api/info，能读到 SPlayer-Next 才算命中
                val request = "GET /api/info HTTP/1.1\r\nHost: $host\r\nConnection: close\r\n\r\n"
                socket.getOutputStream().write(request.toByteArray())
                val response = socket.getInputStream().bufferedReader().use { it.readText() }
                response.contains("SPlayer", ignoreCase = true)
            }
        } catch (e: Exception) {
            false
        }
    }

    /** 取当前 WLAN 的 IPv4 地址，排除回环与虚拟网卡 */
    private fun findLocalIpv4(): String? {
        return try {
            NetworkInterface.getNetworkInterfaces()
                .toList()
                .filter { it.isUp && !it.isLoopback }
                .filter { name -> name.name.lowercase().let { it.startsWith("wlan") || it.startsWith("eth") } }
                .flatMap { it.inetAddresses.toList() }
                .filterIsInstance<Inet4Address>()
                .firstOrNull { addr ->
                    val host = addr.hostAddress ?: return@firstOrNull false
                    !host.startsWith("127.") && !host.startsWith("169.254.")
                }
                ?.hostAddress
        } catch (e: Exception) {
            null
        }
    }
}

package com.focuslock.detox

import android.app.Notification
import android.app.NotificationChannel
import android.app.NotificationManager
import android.app.PendingIntent
import android.content.Intent
import android.net.VpnService
import android.os.Build
import android.os.ParcelFileDescriptor
import android.util.Log
import androidx.core.app.NotificationCompat
import java.io.FileInputStream
import java.io.FileOutputStream
import java.net.DatagramPacket
import java.net.DatagramSocket
import java.net.InetAddress
import java.nio.ByteBuffer
import java.util.concurrent.atomic.AtomicBoolean
import java.util.concurrent.Executors
import java.util.concurrent.ExecutorService
import java.util.concurrent.ConcurrentHashMap

/**
 * DNS 필터링 방식 VPN 서비스
 * - 브라우저 앱만 VPN 터널로 가져옴 (addAllowedApplication)
 * - DNS 요청(포트 53)만 가로채서 필터링
 * - 차단 도메인은 0.0.0.0으로 응답
 * - 일반 트래픽은 그대로 통과
 */
class BlockerVpnService : VpnService() {
    companion object {
        private const val TAG = "BlockerVpnService"
        private const val CHANNEL_ID = "focuslock_vpn"
        private const val NOTIFICATION_ID = 1

        // DNS 캐시 TTL (60초)
        private const val DNS_CACHE_TTL = 60_000L

        // 차단할 도메인 목록 (HashSet으로 O(1) 검색)
        val BLOCKED_DOMAINS = hashSetOf(
            // YouTube
            "youtube.com",
            "www.youtube.com",
            "m.youtube.com",
            "youtu.be",
            "youtube-nocookie.com",
            "youtubei.googleapis.com",
            "yt3.ggpht.com",
            "music.youtube.com",
            "studio.youtube.com",

            // Instagram
            "instagram.com",
            "www.instagram.com",
            "i.instagram.com",
            "graph.instagram.com",
            "api.instagram.com",
            "l.instagram.com",
            "static.cdninstagram.com",
            "scontent.cdninstagram.com",

            // Chzzk (치지직)
            "chzzk.naver.com",
            "api.chzzk.naver.com",
            "live.chzzk.naver.com",
            "m.chzzk.naver.com",

            // LoL 관련
            "leagueoflegends.com",
            "www.leagueoflegends.com",
            "op.gg",
            "www.op.gg",
            "fow.kr",
            "www.fow.kr"
        )

        // 브라우저 앱 패키지명 목록
        val BROWSER_PACKAGES = listOf(
            "com.android.chrome",
            "com.chrome.beta",
            "com.chrome.dev",
            "com.chrome.canary",
            "com.sec.android.app.sbrowser",  // Samsung Internet (실제 패키지명)
            "com.sec.android.app.sbrowser.beta",
            "com.samsung.android.app.sbrowser",  // Samsung Internet 대체
            "org.mozilla.firefox",
            "org.mozilla.firefox_beta",
            "com.opera.browser",
            "com.opera.mini.native",
            "com.microsoft.emmx",  // Edge
            "com.brave.browser",
            "com.naver.whale"  // Naver Whale
        )

        @Volatile
        var isRunning = false
            private set
    }

    private var vpnInterface: ParcelFileDescriptor? = null
    private val shouldRun = AtomicBoolean(false)
    private var vpnThread: Thread? = null
    private var dnsExecutor: ExecutorService? = null

    // DNS 캐시: 도메인 -> (응답 데이터, 만료 시간)
    private val dnsCache = ConcurrentHashMap<String, Pair<ByteArray, Long>>()

    override fun onCreate() {
        super.onCreate()
        createNotificationChannel()
    }

    override fun onStartCommand(intent: Intent?, flags: Int, startId: Int): Int {
        Log.i(TAG, "onStartCommand called, action=${intent?.action}, isLocked=${BlockerManager.isLocked}")

        if (intent?.action == "STOP") {
            Log.i(TAG, "Stopping VPN due to STOP action")
            stopVpn()
            return START_NOT_STICKY
        }

        if (!BlockerManager.isLocked) {
            Log.w(TAG, "Not locked, stopping VPN service")
            stopSelf()
            return START_NOT_STICKY
        }

        Log.i(TAG, "Starting foreground service and VPN...")
        startForeground(NOTIFICATION_ID, createNotification())
        startVpn()

        return START_STICKY
    }

    private fun startVpn() {
        if (isRunning) {
            Log.w(TAG, "VPN already running")
            return
        }

        try {
            val builder = Builder()
                .setSession("FocusLock DNS Filter")
                .addAddress("10.0.0.2", 32)
                // DNS 서버 주소만 VPN으로 라우팅 (다른 트래픽은 일반 네트워크 사용)
                .addRoute("8.8.8.8", 32)
                .addRoute("8.8.4.4", 32)
                .addDnsServer("8.8.8.8")
                .addDnsServer("8.8.4.4")
                .setMtu(1500)

            // 브라우저 앱만 VPN에 포함 (핵심!)
            // 다른 앱(게임, 카톡 등)은 VPN 영향을 받지 않음
            var addedApps = 0
            Log.i(TAG, "Checking ${BROWSER_PACKAGES.size} browser packages...")
            for (packageName in BROWSER_PACKAGES) {
                try {
                    packageManager.getPackageInfo(packageName, 0)
                    builder.addAllowedApplication(packageName)
                    Log.i(TAG, "Added browser to VPN: $packageName")
                    addedApps++
                } catch (e: Exception) {
                    // 브라우저 미설치 - 무시
                }
            }

            // YouTube 앱도 추가 (앱 자체 차단)
            try {
                packageManager.getPackageInfo("com.google.android.youtube", 0)
                builder.addAllowedApplication("com.google.android.youtube")
                addedApps++
            } catch (e: Exception) {}

            // Instagram 앱도 추가
            try {
                packageManager.getPackageInfo("com.instagram.android", 0)
                builder.addAllowedApplication("com.instagram.android")
                addedApps++
            } catch (e: Exception) {}

            if (addedApps == 0) {
                Log.e(TAG, "No browser apps found to filter")
                stopSelf()
                return
            }

            vpnInterface = builder.establish()
            if (vpnInterface == null) {
                Log.e(TAG, "Failed to establish VPN interface")
                stopSelf()
                return
            }

            shouldRun.set(true)
            isRunning = true

            // DNS 포워딩용 스레드풀 생성 (2개로 충분)
            dnsExecutor = Executors.newFixedThreadPool(2)

            // DNS 캐시 초기화
            dnsCache.clear()

            vpnThread = Thread { runVpnLoop() }
            vpnThread?.start()

            Log.i(TAG, "VPN started with DNS filtering for $addedApps apps")

        } catch (e: Exception) {
            Log.e(TAG, "Failed to start VPN", e)
            stopSelf()
        }
    }

    private fun runVpnLoop() {
        val vpnFd = vpnInterface?.fileDescriptor ?: return
        val inputStream = FileInputStream(vpnFd)
        val outputStream = FileOutputStream(vpnFd)
        // DNS 패킷은 보통 512 bytes, 최대 4KB면 충분
        val buffer = ByteBuffer.allocate(4096)

        Log.i(TAG, "VPN loop started")

        while (shouldRun.get()) {
            try {
                buffer.clear()
                // FileInputStream.read()는 블로킹이므로 데이터가 올 때까지 대기
                // available()로 먼저 체크하면 busy-wait가 되므로 직접 read() 호출
                val length = inputStream.read(buffer.array())

                if (length > 0) {
                    buffer.limit(length)
                    processPacket(buffer, outputStream)
                } else if (length < 0) {
                    // EOF - VPN 인터페이스가 닫힘
                    Log.w(TAG, "VPN interface closed")
                    break
                }
                // length == 0인 경우는 거의 없지만, CPU 과부하 방지를 위해 짧은 대기
            } catch (e: Exception) {
                if (shouldRun.get()) {
                    Log.e(TAG, "Error in VPN loop", e)
                    // 에러 발생 시 잠시 대기 후 재시도
                    Thread.sleep(100)
                } else {
                    break
                }
            }
        }

        Log.i(TAG, "VPN loop ended")
    }

    private fun processPacket(buffer: ByteBuffer, outputStream: FileOutputStream) {
        if (buffer.limit() < 20) return  // IP 헤더 최소 크기

        val version = (buffer.get(0).toInt() and 0xF0) shr 4
        if (version != 4) return  // IPv4만 처리

        val headerLength = (buffer.get(0).toInt() and 0x0F) * 4
        val protocol = buffer.get(9).toInt() and 0xFF

        // UDP (17) 프로토콜만 처리
        if (protocol != 17) {
            // UDP가 아닌 패킷은 그대로 전달 (TCP 등)
            forwardPacket(buffer)
            return
        }

        if (buffer.limit() < headerLength + 8) return  // UDP 헤더 최소 크기

        val destPort = buffer.getShort(headerLength + 2).toInt() and 0xFFFF

        // DNS 요청 (포트 53)만 필터링
        if (destPort == 53) {
            handleDnsRequest(buffer, headerLength, outputStream)
        } else {
            // DNS가 아닌 UDP 패킷은 그대로 전달
            forwardPacket(buffer)
        }
    }

    private fun handleDnsRequest(buffer: ByteBuffer, ipHeaderLength: Int, outputStream: FileOutputStream) {
        val udpHeaderLength = 8
        val dnsOffset = ipHeaderLength + udpHeaderLength

        if (buffer.limit() < dnsOffset + 12) return  // DNS 헤더 최소 크기

        // DNS 쿼리에서 도메인 이름 추출
        val domain = extractDomainFromDns(buffer, dnsOffset + 12)

        if (domain != null && shouldBlockDomain(domain)) {
            Log.i(TAG, "Blocking DNS request for: $domain")
            // 차단: NXDOMAIN 응답 생성
            val fakeResponse = createFakeDnsResponse(buffer, ipHeaderLength)
            if (fakeResponse != null) {
                outputStream.write(fakeResponse)
            }
        } else if (domain != null) {
            // 캐시 확인
            val cached = dnsCache[domain]
            val now = System.currentTimeMillis()

            if (cached != null && cached.second > now) {
                // 캐시 히트: 캐시된 응답 사용
                val cachedResponse = buildCachedDnsResponse(buffer, ipHeaderLength, cached.first)
                if (cachedResponse != null) {
                    outputStream.write(cachedResponse)
                    return
                }
            }

            // 캐시 미스 또는 만료: 실제 DNS 서버로 전달
            forwardDnsRequest(buffer, ipHeaderLength, outputStream, domain)
        } else {
            // 도메인 추출 실패: 그냥 전달
            forwardDnsRequest(buffer, ipHeaderLength, outputStream, null)
        }
    }

    private fun buildCachedDnsResponse(buffer: ByteBuffer, ipHeaderLength: Int, cachedDnsData: ByteArray): ByteArray? {
        return buildDnsResponsePacket(buffer.array().copyOf(buffer.limit()), ipHeaderLength, cachedDnsData)
    }

    private fun extractDomainFromDns(buffer: ByteBuffer, offset: Int): String? {
        try {
            val sb = StringBuilder()
            var pos = offset

            while (pos < buffer.limit()) {
                val len = buffer.get(pos).toInt() and 0xFF
                if (len == 0) break

                if (sb.isNotEmpty()) sb.append('.')

                for (i in 1..len) {
                    if (pos + i >= buffer.limit()) return null
                    sb.append(buffer.get(pos + i).toInt().toChar())
                }
                pos += len + 1
            }

            return sb.toString().lowercase()
        } catch (e: Exception) {
            return null
        }
    }

    private fun shouldBlockDomain(domain: String): Boolean {
        if (!BlockerManager.isLocked) return false

        return BLOCKED_DOMAINS.any { blocked ->
            domain == blocked || domain.endsWith(".$blocked")
        }
    }

    private fun createFakeDnsResponse(buffer: ByteBuffer, ipHeaderLength: Int): ByteArray? {
        try {
            val udpHeaderLength = 8
            val dnsOffset = ipHeaderLength + udpHeaderLength

            // 원본 패킷 복사
            val response = ByteArray(buffer.limit())
            buffer.position(0)
            buffer.get(response)

            // IP 헤더: 소스/목적지 주소 스왑
            val srcAddr = ByteArray(4)
            val dstAddr = ByteArray(4)
            System.arraycopy(response, 12, srcAddr, 0, 4)
            System.arraycopy(response, 16, dstAddr, 0, 4)
            System.arraycopy(dstAddr, 0, response, 12, 4)
            System.arraycopy(srcAddr, 0, response, 16, 4)

            // UDP 헤더: 소스/목적지 포트 스왑
            val srcPort = ByteArray(2)
            val dstPort = ByteArray(2)
            System.arraycopy(response, ipHeaderLength, srcPort, 0, 2)
            System.arraycopy(response, ipHeaderLength + 2, dstPort, 0, 2)
            System.arraycopy(dstPort, 0, response, ipHeaderLength, 2)
            System.arraycopy(srcPort, 0, response, ipHeaderLength + 2, 2)

            // DNS 헤더: 응답 플래그 설정
            response[dnsOffset + 2] = 0x81.toByte()  // QR=1, Opcode=0, AA=0, TC=0, RD=1
            response[dnsOffset + 3] = 0x83.toByte()  // RA=1, NXDOMAIN (도메인 없음)

            // IP 체크섬 재계산
            recalculateIpChecksum(response, ipHeaderLength)

            // UDP 체크섬은 0으로 설정 (선택적)
            response[ipHeaderLength + 6] = 0
            response[ipHeaderLength + 7] = 0

            return response
        } catch (e: Exception) {
            Log.e(TAG, "Error creating fake DNS response", e)
            return null
        }
    }

    private fun recalculateIpChecksum(packet: ByteArray, headerLength: Int) {
        // 기존 체크섬 클리어
        packet[10] = 0
        packet[11] = 0

        var sum = 0
        for (i in 0 until headerLength step 2) {
            val word = ((packet[i].toInt() and 0xFF) shl 8) or (packet[i + 1].toInt() and 0xFF)
            sum += word
        }

        while (sum shr 16 != 0) {
            sum = (sum and 0xFFFF) + (sum shr 16)
        }

        val checksum = sum.inv() and 0xFFFF
        packet[10] = (checksum shr 8).toByte()
        packet[11] = (checksum and 0xFF).toByte()
    }

    private fun forwardPacket(buffer: ByteBuffer) {
        // 일반 패킷은 시스템에 맡김 (VPN이 허용한 앱의 트래픽)
        // 실제로는 별도의 터널링이 필요하지만,
        // addAllowedApplication으로 브라우저만 지정했으므로
        // 다른 앱의 트래픽은 VPN을 거치지 않음
    }

    private fun forwardDnsRequest(buffer: ByteBuffer, ipHeaderLength: Int, outputStream: FileOutputStream, domain: String?) {
        try {
            val udpHeaderLength = 8
            val dnsOffset = ipHeaderLength + udpHeaderLength
            val dnsLength = buffer.limit() - dnsOffset

            if (dnsLength <= 0) {
                return
            }

            // 원본 패킷 정보 저장 (응답 생성에 필요)
            val originalPacket = ByteArray(buffer.limit())
            buffer.position(0)
            buffer.get(originalPacket)

            // DNS 데이터 추출
            val dnsData = ByteArray(dnsLength)
            System.arraycopy(originalPacket, dnsOffset, dnsData, 0, dnsLength)

            // 비동기로 DNS 쿼리 수행 (VPN 루프 블로킹 방지)
            dnsExecutor?.submit {
                var socket: DatagramSocket? = null
                try {
                    socket = DatagramSocket()
                    protect(socket)  // VPN 루프 방지

                    val dnsServer = InetAddress.getByName("8.8.8.8")
                    val packet = DatagramPacket(dnsData, dnsData.size, dnsServer, 53)

                    socket.soTimeout = 3000
                    socket.send(packet)

                    val responseBuffer = ByteArray(1024)
                    val responsePacket = DatagramPacket(responseBuffer, responseBuffer.size)
                    socket.receive(responsePacket)

                    // DNS 응답을 IP 패킷으로 감싸서 VPN 인터페이스로 전송
                    val responseData = responsePacket.data.copyOfRange(0, responsePacket.length)

                    // 캐시에 저장 (60초 TTL)
                    if (domain != null) {
                        dnsCache[domain] = Pair(responseData, System.currentTimeMillis() + DNS_CACHE_TTL)

                        // 캐시 크기 제한 (100개 초과 시 오래된 항목 제거)
                        if (dnsCache.size > 100) {
                            cleanupDnsCache()
                        }
                    }

                    val vpnResponse = buildDnsResponsePacket(originalPacket, ipHeaderLength, responseData)

                    if (vpnResponse != null) {
                        synchronized(outputStream) {
                            outputStream.write(vpnResponse)
                            outputStream.flush()
                        }
                    }
                } catch (e: Exception) {
                    Log.e(TAG, "Error forwarding DNS: ${e.message}")
                } finally {
                    socket?.close()
                }
            }
        } catch (e: Exception) {
            Log.e(TAG, "Error in forwardDnsRequest: ${e.message}")
        }
    }

    private fun cleanupDnsCache() {
        val now = System.currentTimeMillis()
        val iterator = dnsCache.entries.iterator()
        while (iterator.hasNext()) {
            val entry = iterator.next()
            if (entry.value.second < now) {
                iterator.remove()
            }
        }
    }

    private fun buildDnsResponsePacket(originalPacket: ByteArray, ipHeaderLength: Int, dnsResponse: ByteArray): ByteArray? {
        try {
            val udpHeaderLength = 8
            val totalLength = ipHeaderLength + udpHeaderLength + dnsResponse.size

            val response = ByteArray(totalLength)

            // IP 헤더 복사
            System.arraycopy(originalPacket, 0, response, 0, ipHeaderLength)

            // IP 헤더 수정: 소스/목적지 주소 스왑
            val srcAddr = ByteArray(4)
            val dstAddr = ByteArray(4)
            System.arraycopy(originalPacket, 12, srcAddr, 0, 4)
            System.arraycopy(originalPacket, 16, dstAddr, 0, 4)
            System.arraycopy(dstAddr, 0, response, 12, 4)
            System.arraycopy(srcAddr, 0, response, 16, 4)

            // IP 헤더: Total Length 업데이트
            response[2] = (totalLength shr 8).toByte()
            response[3] = (totalLength and 0xFF).toByte()

            // UDP 헤더 복사
            System.arraycopy(originalPacket, ipHeaderLength, response, ipHeaderLength, udpHeaderLength)

            // UDP 헤더 수정: 소스/목적지 포트 스왑
            val srcPort = ByteArray(2)
            val dstPort = ByteArray(2)
            System.arraycopy(originalPacket, ipHeaderLength, srcPort, 0, 2)
            System.arraycopy(originalPacket, ipHeaderLength + 2, dstPort, 0, 2)
            System.arraycopy(dstPort, 0, response, ipHeaderLength, 2)
            System.arraycopy(srcPort, 0, response, ipHeaderLength + 2, 2)

            // UDP 헤더: Length 업데이트
            val udpLength = udpHeaderLength + dnsResponse.size
            response[ipHeaderLength + 4] = (udpLength shr 8).toByte()
            response[ipHeaderLength + 5] = (udpLength and 0xFF).toByte()

            // UDP 체크섬 비활성화 (0으로 설정)
            response[ipHeaderLength + 6] = 0
            response[ipHeaderLength + 7] = 0

            // DNS 응답 데이터 복사
            System.arraycopy(dnsResponse, 0, response, ipHeaderLength + udpHeaderLength, dnsResponse.size)

            // IP 체크섬 재계산
            recalculateIpChecksum(response, ipHeaderLength)

            return response
        } catch (e: Exception) {
            Log.e(TAG, "Error building DNS response packet", e)
            return null
        }
    }

    private fun stopVpn() {
        shouldRun.set(false)
        isRunning = false

        vpnThread?.interrupt()
        vpnThread = null

        dnsExecutor?.shutdownNow()
        dnsExecutor = null

        // DNS 캐시 정리
        dnsCache.clear()

        vpnInterface?.close()
        vpnInterface = null

        stopForeground(STOP_FOREGROUND_REMOVE)
        stopSelf()

        Log.i(TAG, "VPN stopped")
    }

    override fun onDestroy() {
        stopVpn()
        super.onDestroy()
    }

    private fun createNotificationChannel() {
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.O) {
            val channel = NotificationChannel(
                CHANNEL_ID,
                "FocusLock VPN",
                NotificationManager.IMPORTANCE_LOW
            ).apply {
                description = "FocusLock 웹사이트 차단 서비스"
                setShowBadge(false)
            }

            val manager = getSystemService(NotificationManager::class.java)
            manager.createNotificationChannel(channel)
        }
    }

    private fun createNotification(): Notification {
        val pendingIntent = PendingIntent.getActivity(
            this, 0,
            Intent(this, MainActivity::class.java),
            PendingIntent.FLAG_UPDATE_CURRENT or PendingIntent.FLAG_IMMUTABLE
        )

        val remainingMinutes = BlockerManager.getRemainingSeconds() / 60

        return NotificationCompat.Builder(this, CHANNEL_ID)
            .setContentTitle("FocusLock 활성화")
            .setContentText("웹사이트 차단 중 - ${remainingMinutes}분 남음")
            .setSmallIcon(android.R.drawable.ic_lock_lock)
            .setContentIntent(pendingIntent)
            .setOngoing(true)
            .setPriority(NotificationCompat.PRIORITY_LOW)
            .build()
    }
}

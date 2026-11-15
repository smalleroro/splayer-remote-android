package top.dsh.splayerremote.data

import kotlinx.coroutines.test.runTest
import okhttp3.mockwebserver.MockResponse
import okhttp3.mockwebserver.MockWebServer
import org.junit.After
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Assert.fail
import org.junit.Before
import org.junit.Test

/**
 * 用 MockWebServer 验证真实客户端的解析与错误分层。
 *
 * 这一组测试针对的正是之前的两个静默 bug：
 *  - 200 但 `{"ok":false}` 被当成成功；
 *  - 400 的 `error` 字段没被读出来，界面只能看到裸 HTTP 码。
 */
class HttpSPlayerApiTest {

    private lateinit var server: MockWebServer

    @Before
    fun setUp() {
        server = MockWebServer()
        server.start()
    }

    @After
    fun tearDown() {
        server.shutdown()
    }

    private fun api() = HttpSPlayerApi(server.url("/").toString().trimEnd('/'))

    @Test
    fun `parses status response`() = runTest {
        server.enqueue(
            MockResponse().setBody(
                """{"state":"playing","position":12000,"duration":240000,"volume":0.5,
                   "isFinished":false,"currentIndex":3}"""
            )
        )

        val status = api().status()

        assertEquals("playing", status.state)
        assertEquals(12_000L, status.position)
        assertEquals(240_000L, status.duration)
        assertEquals(0.5f, status.volume, 0.001f)
        assertEquals(3, status.currentIndex)
        assertTrue(status.isPlaying)
    }

    @Test
    fun `unknown fields do not break parsing`() = runTest {
        // 音源字段不齐是常态，多出来的字段不该让整次解析失败
        server.enqueue(
            MockResponse().setBody(
                """{"state":"paused","position":0,"duration":0,"volume":1,
                   "isFinished":false,"currentIndex":-1,"futureField":"x"}"""
            )
        )
        val status = api().status()
        assertEquals("paused", status.state)
    }

    @Test
    fun `business failure is raised when ok is false`() = runTest {
        server.enqueue(MockResponse().setBody("""{"ok":false,"error":"曲目不可播放"}"""))
        try {
            api().next()
            fail("应当抛出 SPlayerBusinessException")
        } catch (e: SPlayerBusinessException) {
            assertEquals("曲目不可播放", e.message)
        }
    }

    @Test
    fun `http error carries server message`() = runTest {
        server.enqueue(
            MockResponse().setResponseCode(400).setBody("""{"error":"positionMs required"}""")
        )
        try {
            api().seek(-1)
            fail("应当抛出 SPlayerHttpException")
        } catch (e: SPlayerHttpException) {
            assertEquals(400, e.code)
            assertEquals("positionMs required", e.message)
        }
    }

    @Test
    fun `seek sends positionMs json body`() = runTest {
        server.enqueue(MockResponse().setBody("""{"ok":true}"""))
        api().seek(42_000)

        val request = server.takeRequest()
        assertEquals("/api/seek", request.path)
        val body = request.body.readUtf8()
        assertTrue("请求体应包含 positionMs，实际 $body", body.contains("\"positionMs\":42000"))
    }

    @Test
    fun `volume is clamped into range`() = runTest {
        server.enqueue(MockResponse().setBody("""{"ok":true}"""))
        api().setVolume(1.5f)

        val body = server.takeRequest().body.readUtf8()
        assertTrue("音量应被夹到 1.0，实际 $body", body.contains("\"volume\":1.0"))
    }

    @Test
    fun `lyrics are parsed with word timings`() = runTest {
        server.enqueue(
            MockResponse().setBody(
                """{"trackId":"1","lyric":[{"words":[{"word":"a","startTime":0,"endTime":100}],
                   "translatedLyric":"甲","startTime":0,"endTime":100}],
                   "source":{"source":"online","format":"ttml","platform":"netease","wordTimed":true},
                   "lyricOffsetMs":0}"""
            )
        )
        val lyrics = api().lyrics()
        assertEquals(1, lyrics.lyric.size)
        assertEquals("a", lyrics.lyric[0].text)
        assertEquals("甲", lyrics.lyric[0].translatedLyric)
        assertEquals("ttml", lyrics.source?.format)
    }

    @Test
    fun `base url normalization`() {
        assertEquals("http://1.2.3.4:14558", SPlayerApi.normalizeBaseUrl("1.2.3.4:14558"))
        assertEquals("http://1.2.3.4:14558", SPlayerApi.normalizeBaseUrl("http://1.2.3.4:14558/"))
        assertEquals(SPlayerApi.DEFAULT_BASE_URL, SPlayerApi.normalizeBaseUrl("   "))
    }
}

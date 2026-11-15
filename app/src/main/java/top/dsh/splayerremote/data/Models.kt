package top.dsh.splayerremote.data

import kotlinx.serialization.SerialName
import kotlinx.serialization.Serializable

/** 艺术家，对应 SPlayer Track.artists 数组元素 */
@Serializable
data class Artist(
    val id: String = "",
    val name: String = "",
)

/** 专辑信息 */
@Serializable
data class Album(
    val id: String = "",
    val name: String = "",
    val cover: String? = null,
)

/** 音质信息，部分来源不返回 */
@Serializable
data class Quality(
    val codec: String? = null,
    val sampleRate: Int? = null,
    val bitsPerSample: Int? = null,
    val bitRate: Int? = null,
    val channels: Int? = null,
)

/**
 * 曲目。
 *
 * 字段与 SPlayer 的 Track 保持一致，但全部给了默认值——
 * 不同音源返回的字段并不齐整，缺字段不应该让整个列表解析失败。
 */
@Serializable
data class Track(
    val id: String = "",
    val source: String = "",
    val title: String = "",
    val artists: List<Artist> = emptyList(),
    val album: Album? = null,
    val duration: Long = 0L,
    val cover: String? = null,
    val quality: Quality? = null,
) {
    /** 「歌名 - 歌手A / 歌手B」，列表与通知栏共用 */
    val displayTitle: String
        get() {
            val names = artists.map { it.name }.filter { it.isNotBlank() }.joinToString(" / ")
            return if (names.isBlank()) title else "$title - $names"
        }
}

/** GET /api/status 的响应 */
@Serializable
data class PlaybackStatus(
    val state: String = "idle",
    val position: Long = 0L,
    val duration: Long = 0L,
    val volume: Float = 1f,
    val isFinished: Boolean = false,
    val currentIndex: Int = -1,
) {
    val isPlaying: Boolean get() = state == "playing"
}

/**
 * 一次采样的结果：值 + 拿到它的本地时刻。
 *
 * 之前的做法是往 PlaybackStatus 里塞一个 fetchedAt 字段，
 * 那是把传输层的信息长进了服务端 DTO 里——服务端并没有这个字段，
 * 而所有消费这个 DTO 的地方都得假装它有意义。包一层干净得多。
 */
data class Sample<T>(val value: T, val fetchedAt: Long) {
    fun map(transform: (T) -> T): Sample<T> = Sample(transform(value), fetchedAt)
}

/** GET /api/now-playing 的响应 */
@Serializable
data class NowPlaying(
    val track: Track? = null,
    val position: Long = 0L,
    val playing: Boolean = false,
    val state: String = "idle",
    val speed: Float = 1f,
    @SerialName("lyricAvailable") val lyricAvailable: Boolean = false,
    @SerialName("lyricLineCount") val lyricLineCount: Int = 0,
)

/** GET /api/queue 的响应 */
@Serializable
data class PlaybackQueue(
    val tracks: List<Track> = emptyList(),
    val total: Int = 0,
    val currentIndex: Int = -1,
    val offset: Int = 0,
)

/** GET /api/info 的响应 */
@Serializable
data class ServerInfo(
    val name: String = "",
    val version: String = "",
    val wsClients: Int = 0,
)

/** 控制接口（POST）的统一响应 */
@Serializable
data class OkResponse(
    val ok: Boolean = false,
    val error: String? = null,
)

/* ------------------------------------------------------------------ 歌词 */

/** 歌词中的一个词（逐字歌词时 startTime/endTime 各不相同） */
@Serializable
data class LyricWord(
    val startTime: Long = 0L,
    val endTime: Long = 0L,
    val word: String = "",
)

/** 一行歌词 */
@Serializable
data class LyricLine(
    val words: List<LyricWord> = emptyList(),
    val translatedLyric: String = "",
    val romanLyric: String = "",
    val startTime: Long = 0L,
    val endTime: Long = 0L,
    val isBG: Boolean = false,
    val isDuet: Boolean = false,
    val language: String = "",
) {
    /** 整行原文，把各个 word 拼起来 */
    val text: String
        get() = words.joinToString("") { it.word }
}

/** GET /api/lyrics 的响应 */
@Serializable
data class Lyrics(
    val trackId: String = "",
    val lyric: List<LyricLine> = emptyList(),
    val source: LyricSource? = null,
    val lyricOffsetMs: Long = 0L,
)

/** 歌词来源，只用来在界面上标注是哪个平台给的 */
@Serializable
data class LyricSource(
    val source: String = "",
    val format: String = "",
    val platform: String = "",
    val wordTimed: Boolean = false,
)

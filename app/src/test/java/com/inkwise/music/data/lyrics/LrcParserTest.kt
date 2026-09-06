package com.inkwise.music.data.lyrics

/**
 * [LrcParser] 的单元测试。
 *
 * 覆盖增强型逐字 LRC 的解析（词级 token、行末时间修正、同时间戳翻译合并、
 * 多时间标签复制、逐字格式判定与防御）、普通行级 LRC（毫秒精度、宽容时间戳）、
 * 以及细空格内联翻译等真实歌曲文件中的变体格式。
 */
import com.inkwise.music.data.model.LyricsSource
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

class LrcParserTest {

    // 验证增强型 LRC 能解析出词级 token 及各自起止时间，且行文本拼接正确
    @Test
    fun `enhanced lrc 解析词级 token`() {
        val content = """
            [ti:测试]
            [00:10.00]<00:10.00>你<00:10.50>好<00:11.00>世<00:11.50>界
            [00:20.00]<00:20.00>再<00:20.50>见
        """.trimIndent()

        val lyrics = LrcParser.parse(content, songId = 1L, source = LyricsSource.LOCAL_LRC)

        assertNotNull(lyrics)
        assertEquals(2, lyrics!!.lines.size)
        val line1 = lyrics.lines[0]
        assertEquals(10000L, line1.timeMs)
        assertEquals("你好世界", line1.text)
        val tokens = line1.tokens!!
        assertEquals(4, tokens.size)
        assertEquals("你", tokens[0].text)
        assertEquals(10000L, tokens[0].startMs)
        assertEquals(10500L, tokens[0].endMs)
        assertEquals("好", tokens[1].text)
        assertEquals(10500L, tokens[1].startMs)
        assertEquals(11000L, tokens[1].endMs)
        assertEquals("界", tokens[3].text)
        assertEquals("再见", lyrics.lines[1].text)
    }

    // 验证行末 token 的结束时间被下一行起始时间修正，且跨度过长时封顶为 start+5s
    @Test
    fun `enhanced lrc 行末token结束时间由下一行修正且不超过5秒`() {
        val content = """
            [00:10.00]<00:10.00>你<00:10.50>好
            [00:12.00]<00:12.00>下<00:12.50>一<00:13.00>行
        """.trimIndent()

        val lyrics = LrcParser.parse(content, 1L, LyricsSource.LOCAL_LRC)!!

        // "好" 的结束时间应被下一行起点(12s)修正，而不是占位的 10.5s+500ms
        val last = lyrics.lines[0].tokens!!.last()
        assertEquals("好", last.text)
        assertEquals(12000L, last.endMs)
        // 间奏过远时封顶 start+5s
        val content2 = """
            [00:10.00]<00:10.00>你<00:10.50>好
            [00:30.00]<00:30.00>下一行
        """.trimIndent()
        val lyrics2 = LrcParser.parse(content2, 1L, LyricsSource.LOCAL_LRC)!!
        val last2 = lyrics2.lines[0].tokens!!.last()
        assertEquals(10500L + 5000L, last2.endMs) // min(30s, 10.5s+5s)
    }

    // 验证增强型文件中普通行级行不产生 token，保持 tokens 为 null
    @Test
    fun `enhanced 文件中的行级行保持 tokens null`() {
        val content = """
            [00:10.00]<00:10.00>你<00:10.50>好
            [00:20.00]这是普通行
        """.trimIndent()

        val lyrics = LrcParser.parse(content, 1L, LyricsSource.LOCAL_LRC)!!

        assertEquals(2, lyrics.lines.size)
        assertNotNull(lyrics.lines[0].tokens)
        assertNull(lyrics.lines[1].tokens)
        assertEquals("这是普通行", lyrics.lines[1].text)
    }

    // 验证同一时间戳的逐字原文行与纯文本行被合并成“原文 + 翻译”
    @Test
    fun `enhanced 同时间戳行合并为翻译`() {
        val content = """
            [00:10.00]<00:10.00>你<00:10.50>好
            [00:10.00]Hello
        """.trimIndent()

        val lyrics = LrcParser.parse(content, 1L, LyricsSource.LOCAL_LRC)!!

        assertEquals(1, lyrics.lines.size)
        assertEquals("你好", lyrics.lines[0].text)
        assertEquals("Hello", lyrics.lines[0].translation)
    }

    // 验证一行带多个时间标签时，按标签数复制为多条相同文本的 token 行
    @Test
    fun `enhanced 多行级标签复制 token 行`() {
        val content = """
            [00:10.00][00:15.00]<00:10.00>副<00:10.50>歌
        """.trimIndent()

        val lyrics = LrcParser.parse(content, 1L, LyricsSource.LOCAL_LRC)!!

        assertEquals(2, lyrics.lines.size)
        assertEquals(10000L, lyrics.lines[0].timeMs)
        assertEquals(15000L, lyrics.lines[1].timeMs)
        assertEquals("副歌", lyrics.lines[0].text)
        assertEquals("副歌", lyrics.lines[1].text)
    }

    // 验证逐字符各带行级时间戳的格式（无 <> 标签）仍能解析出词级 token
    @Test
    fun `多时间标签逐字格式仍可解析`() {
        // 每字符一个行级时间戳的格式（无 <> 标签）
        val content = buildString {
            append("[00:10.000]你[00:10.300]好[00:10.600]世[00:10.900]界[00:11.200]\n")
            append("[00:20.000]再[00:20.300]见[00:20.600]\n")
        }

        val lyrics = LrcParser.parse(content, 1L, LyricsSource.LOCAL_LRC)!!

        assertEquals(2, lyrics.lines.size)
        val tokens = lyrics.lines[0].tokens!!
        assertEquals(4, tokens.size)
        assertEquals("你", tokens[0].text)
        assertEquals(10000L, tokens[0].startMs)
        assertEquals(10300L, tokens[0].endMs)
        assertEquals("界", tokens[3].text)
    }

    // 验证逐字格式下同时间戳、仅一个行级标签的翻译行不再被丢弃，且间奏空标签行被忽略
    @Test
    fun `逐字格式同时间戳单标签翻译行合并`() {
        // 真实样本（心淡 - 容祖儿.flac 内嵌歌词）：原文逐字行 + 同起始时间戳的翻译行，
        // 翻译行只有一个行级时间戳，此前被 parseWordTiming 整行丢弃导致翻译开关不显示
        // 注：前 8 行需过半为多标签行才会判定为逐字格式（与真实文件一致）
        val content = buildString {
            append("[00:10.000]你[00:10.300]好[00:10.600]世[00:10.900]界[00:11.200]\n")
            append("[00:10.000]Hello world\n")
            append("[00:20.000]再[00:20.300]见[00:20.600]了[00:20.900]\n")
            append("[00:20.900]\n") // 间奏空标记行，应忽略
            append("[00:30.000]平[00:30.300]凡[00:30.600]之[00:30.900]歌[00:31.200]\n")
        }

        val lyrics = LrcParser.parse(content, 1L, LyricsSource.LOCAL_LRC)!!

        assertEquals(3, lyrics.lines.size)
        // 原文行逐字 token 保留
        val tokens = lyrics.lines[0].tokens!!
        assertEquals(4, tokens.size)
        assertEquals("你好世界", lyrics.lines[0].text)
        // 同时间戳翻译行合并
        assertEquals("Hello world", lyrics.lines[0].translation)
        assertNull(lyrics.lines[1].translation)
        assertEquals("再见了", lyrics.lines[1].text)
        assertEquals("平凡之歌", lyrics.lines[2].text)
    }

    // 验证逐字判定后未配对的单标签行作为普通行保留（无 token 与翻译）
    @Test
    fun `逐字格式中未配对的单标签行保留为普通行`() {
        val content = buildString {
            append("[00:10.000]你[00:10.300]好[00:10.600]啊[00:10.900]\n")
            append("[00:15.000]间奏后的普通行\n")
        }

        val lyrics = LrcParser.parse(content, 1L, LyricsSource.LOCAL_LRC)!!

        assertEquals(2, lyrics.lines.size)
        assertEquals(15000L, lyrics.lines[1].timeMs)
        assertEquals("间奏后的普通行", lyrics.lines[1].text)
        assertNull(lyrics.lines[1].tokens)
        assertNull(lyrics.lines[1].translation)
    }

    // 验证普通行级 LRC 只解析时间与文本，不产生 token/翻译
    @Test
    fun `普通行级 lrc 不产生 token`() {
        val content = """
            [00:10.00]第一行
            [00:20.50]第二行
        """.trimIndent()

        val lyrics = LrcParser.parse(content, 1L, LyricsSource.LOCAL_LRC)!!

        assertEquals(2, lyrics.lines.size)
        assertNull(lyrics.lines[0].tokens)
        assertNull(lyrics.lines[0].translation)
        assertEquals(20500L, lyrics.lines[1].timeMs)
    }

    // 验证普通 LRC 的毫秒字段同时支持两位（10.50）与三位（20.500）写法
    @Test
    fun `普通 lrc 两位与三位毫秒均支持`() {
        val content = """
            [00:10.50]两位
            [00:20.500]三位
        """.trimIndent()

        val lyrics = LrcParser.parse(content, 1L, LyricsSource.LOCAL_LRC)!!

        assertEquals(10500L, lyrics.lines[0].timeMs)
        assertEquals(20500L, lyrics.lines[1].timeMs)
    }

    // 验证空字符串或仅含元数据（无歌词行）时返回 null 而非空对象
    @Test
    fun `空内容返回 null`() {
        assertNull(LrcParser.parse("", 1L, LyricsSource.LOCAL_LRC))
        assertNull(LrcParser.parse("[ti:只有元数据]", 1L, LyricsSource.LOCAL_LRC))
    }

    // 验证被判定为普通 LRC 时，逐词交错行不会按标签数重复整行文本
    @Test
    fun `普通lrc中逐词交错行不按标签复制`() {
        // 判定为普通 LRC（多标签行占比 < 1/2 且无 ≥5 标签行）时，
        // 逐词交错行仍不按标签数重复整行文本（防御 parsePlain）
        val content = """
            [00:00.453]What's [00:00.764]the [00:00.933]trick[00:01.236]
            [00:05.000]普通行A
            [00:06.000]普通行B
            [00:07.000]普通行C
        """.trimIndent()

        val lyrics = LrcParser.parse(content, 1L, LyricsSource.LOCAL_LRC)!!

        assertEquals(4, lyrics.lines.size)
        assertEquals(453L, lyrics.lines[0].timeMs)
        assertEquals("What's the trick", lyrics.lines[0].text)
        assertNull(lyrics.lines[0].tokens)
    }

    // 验证标签集中含多个时间戳时，该行仍按标签数复制为多条歌词
    @Test
    fun `标签集中的多时间戳复制行仍复制`() {
        val content = """
            [00:10.00][00:20.00]副歌
            [00:30.00]普通行A
            [00:40.00]普通行B
            [00:50.00]普通行C
        """.trimIndent()

        val lyrics = LrcParser.parse(content, 1L, LyricsSource.LOCAL_LRC)!!

        // 副歌复制为 2 条 + 3 条普通行
        assertEquals(5, lyrics.lines.size)
        assertEquals(10_000L, lyrics.lines[0].timeMs)
        assertEquals(20_000L, lyrics.lines[1].timeMs)
        assertEquals("副歌", lyrics.lines[0].text)
        assertEquals("副歌", lyrics.lines[1].text)
    }

    // 验证头部大量版权/空标签行不会稀释逐字判定，避免误落普通解析导致重复行
    @Test
    fun `头部版权空标签行不破坏逐字判定`() {
        // 真实样本（All Falls Down.flac）：头部大量单标签版权行/空标签行稀释占比，
        // 此前逐字判定失败落入 parsePlain，导致每行歌词按标签数重复数遍
        val content = buildString {
            append("[00:00.000]All[00:00.006] [00:00.008]Falls[00:00.018] [00:00.020]Down[00:00.028]\n")
            append("[00:00.000]QQ音乐享有本翻译作品的著作权\n")
            append("[00:00.140]Lyrics[00:00.146] [00:00.152]by[00:00.158]：Pablo\n")
            append("[00:00.140]\n")
            append("[00:00.280]Composed[00:00.285] [00:00.290]by[00:00.295]：Alan\n")
            append("[00:00.280]\n")
            append("[00:00.453]What's [00:00.764]the [00:00.933]trick[00:01.236]\n")
            append("[00:00.453]爱的诀窍是什么\n")
        }

        val lyrics = LrcParser.parse(content, 1L, LyricsSource.LOCAL_LRC)!!

        // 逐字判定成功：无重复行，同时间戳翻译行合并
        assertEquals(4, lyrics.lines.size)
        assertEquals("All Falls Down", lyrics.lines[0].text)
        assertEquals("QQ音乐享有本翻译作品的著作权", lyrics.lines[0].translation)
        assertEquals("What's the trick", lyrics.lines[3].text)
        assertEquals("爱的诀窍是什么", lyrics.lines[3].translation)
    }

    // 验证 U+2009 细空格内联的“原文　翻译”被拆分为同时间戳的翻译行
    @Test
    fun `细空格内联翻译拆分为翻译行`() {
        // 对齐椒盐音乐：U+2009 细空格视作换行，"原文　翻译"同行格式拆分后按同时间戳配对
        val content = "[00:10.000]你好世界\u2009Hello world\n[00:20.000]再见\u2009Goodbye"

        val lyrics = LrcParser.parse(content, 1L, LyricsSource.LOCAL_LRC)!!

        assertEquals(2, lyrics.lines.size)
        assertEquals("你好世界", lyrics.lines[0].text)
        assertEquals("Hello world", lyrics.lines[0].translation)
        assertEquals("再见", lyrics.lines[1].text)
        assertEquals("Goodbye", lyrics.lines[1].translation)
    }

    // 验证无时间戳的后续行继承上一行时间戳并作为翻译合并
    @Test
    fun `无时间戳行继承上一行时间戳成为翻译`() {
        val content = """
            [00:10.000]你好世界
            Hello world
        """.trimIndent()

        val lyrics = LrcParser.parse(content, 1L, LyricsSource.LOCAL_LRC)!!

        assertEquals(1, lyrics.lines.size)
        assertEquals("你好世界", lyrics.lines[0].text)
        assertEquals("Hello world", lyrics.lines[0].translation)
    }

    // 验证宽容时间戳格式：分钟 1-3 位、秒 1-2 位、小数 1-6 位均能正确换算并排序
    @Test
    fun `宽容时间戳格式解析`() {
        // 对齐椒盐：分钟 1-3 位、秒 1-2 位、小数 1-6 位
        val content = """
            [1:01.2760]一位分钟四位小数
            [000:02.5]三位分钟一位小数
            [00:3]无小数
        """.trimIndent()

        val lyrics = LrcParser.parse(content, 1L, LyricsSource.LOCAL_LRC)!!

        assertEquals(3, lyrics.lines.size)
        // 解析结果按时间排序：2.5s < 3s < 61.276s
        assertEquals(2_500L, lyrics.lines[0].timeMs)
        assertEquals(3_000L, lyrics.lines[1].timeMs)
        // 1×60000 + 1×1000 + 2760/10
        assertEquals(61_276L, lyrics.lines[2].timeMs)
    }

    // 验证逐字格式与细空格内联翻译的组合：原文保留 token，翻译行正常合并
    @Test
    fun `逐字格式带内联翻译`() {
        // 逐字原文行 + 细空格内联翻译（心淡.flac 类格式变体）
        val content = buildString {
            append("[00:10.000]你[00:10.300]好[00:10.600]世[00:10.900]界[00:11.200]\u2009Hello world\n")
            append("[00:20.000]再[00:20.300]见[00:20.600]\u2009Goodbye\n")
        }

        val lyrics = LrcParser.parse(content, 1L, LyricsSource.LOCAL_LRC)!!

        assertEquals(2, lyrics.lines.size)
        assertNotNull(lyrics.lines[0].tokens)
        assertEquals("你好世界", lyrics.lines[0].text)
        assertEquals("Hello world", lyrics.lines[0].translation)
        assertEquals("Goodbye", lyrics.lines[1].translation)
    }
}

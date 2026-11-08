package io.github.jackwener.dsh.wake

import kotlinx.serialization.json.Json
import kotlinx.serialization.json.jsonArray
import kotlinx.serialization.json.jsonObject
import kotlinx.serialization.json.jsonPrimitive
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test
import java.io.File

/**
 * The encoder must reproduce the model's own tokenizer exactly.
 *
 * `bpe-golden.json` was produced by running real sentencepiece over the model's `bpe.model`
 * (`SentencePieceProcessor.encode(..., out_type=str)`); see docs/VOICE-WAKE.md. If this
 * drifts, the keyword line is wrong and the spotter either never fires or exits the process.
 */
class TextToBpeGoldenTest {
    private val modelDir = File("src/test/resources/wake")
    private val vocabulary by lazy { TextToBpe.Vocabulary.fromTsvFile(File(modelDir, "vocab.tsv")) }
    private val tokens by lazy { TextToBpe.tokensFrom(File(modelDir, "tokens.txt").readLines().asSequence()) }

    private data class Golden(val phrase: String, val pieces: List<String>)

    private fun goldenCases(): List<Golden> {
        val root = Json.parseToJsonElement(File(modelDir, "bpe-golden.json").readText()).jsonObject
        return root.getValue("cases").jsonArray.map { element ->
            val case = element.jsonObject
            Golden(
                phrase = case.getValue("phrase").jsonPrimitive.content,
                pieces = case.getValue("pieces").jsonArray.map { it.jsonPrimitive.content },
            )
        }
    }

    @Test fun `golden fixture is present and covers the default wake phrase`() {
        val cases = goldenCases()
        assertTrue("fixture parsed too few cases: ${cases.size}", cases.size >= 20)
        assertTrue("fixture must contain HEY DSH", cases.any { it.phrase == "HEY DSH" })
    }

    @Test fun `encoder reproduces sentencepiece for every golden phrase`() {
        for (case in goldenCases()) {
            assertEquals("segmentation of '${case.phrase}'", case.pieces, vocabulary.segment(case.phrase))
        }
    }

    @Test fun `the shipped table is faithful to the model's tokenizer`() {
        // tokens.txt additionally carries the three control pieces, which are never decodable
        // segments and must not take part in a segmentation.
        val controlPieces = setOf("<blk>", "<sos/eos>", "<unk>")
        assertEquals("tokens.txt pieces not in the table", controlPieces, tokens - vocabulary.pieces)
        assertEquals("table pieces missing from tokens.txt", emptySet<String>(), vocabulary.pieces - tokens)
    }

    @Test fun `default wake phrase encodes to a line made only of known tokens`() {
        val result = TextToBpe.encode("HEY DSH", vocabulary, tokens)
        assertTrue("expected Ok, got $result", result is TextToBpe.Result.Ok)
        val ok = result as TextToBpe.Result.Ok
        assertEquals("▁HE Y ▁ D SH", ok.line)
        for (piece in ok.pieces) assertTrue("piece '$piece' missing from tokens.txt", piece in tokens)
    }

    @Test fun `lowercase and extra whitespace tokenize like the model's own normalizer`() {
        val expected = TextToBpe.encode("HEY DSH", vocabulary, tokens)
        assertEquals(expected, TextToBpe.encode("  hey   dsh ", vocabulary, tokens))
    }

    @Test fun `boost and threshold suffixes follow sherpa's keyword-file grammar`() {
        val result = TextToBpe.encode("HEY DSH", vocabulary, tokens, boost = 2.0f, threshold = 0.1f)
        assertTrue(result is TextToBpe.Result.Ok)
        val line = (result as TextToBpe.Result.Ok).line
        assertTrue("threshold suffix missing from '$line'", line.contains("#0.1"))
        assertTrue("boost suffix missing from '$line'", line.endsWith(":2.0"))
    }

    @Test fun `every golden phrase encodes without an unknown piece`() {
        for (case in goldenCases()) {
            val result = TextToBpe.encode(case.phrase, vocabulary, tokens)
            assertTrue("'${case.phrase}' -> $result", result is TextToBpe.Result.Ok)
        }
    }

    @Test fun `text with no possible segmentation is reported rather than guessed`() {
        // A CJK phrase has no pieces in an English BPE vocabulary.
        val result = TextToBpe.encode("打开设置", vocabulary, tokens)
        assertTrue("expected UnknownPieces, got $result", result is TextToBpe.Result.UnknownPieces)
    }
}

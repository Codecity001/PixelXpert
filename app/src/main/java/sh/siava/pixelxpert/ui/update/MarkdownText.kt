package sh.siava.pixelxpert.ui.update

import androidx.compose.foundation.background
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxHeight
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.produceState
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.text.AnnotatedString
import androidx.compose.ui.text.LinkAnnotation
import androidx.compose.ui.text.SpanStyle
import androidx.compose.ui.text.TextLinkStyles
import androidx.compose.ui.text.buildAnnotatedString
import androidx.compose.ui.text.font.FontFamily
import androidx.compose.ui.text.font.FontStyle
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextDecoration
import androidx.compose.ui.text.withStyle
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import org.intellij.markdown.MarkdownElementTypes
import org.intellij.markdown.MarkdownTokenTypes
import org.intellij.markdown.ast.ASTNode
import org.intellij.markdown.flavours.gfm.GFMFlavourDescriptor
import org.intellij.markdown.parser.MarkdownParser

/**
 * Draws markdown as ordinary Compose text.
 *
 * Parsing happens off the main thread and the result is a tree of composables,
 * so the text sits in whatever scroll container the caller placed it in rather
 * than owning a scroll region of its own. That is the whole point: a nested view
 * with its own scroller competes with a scrolling parent for the same drag, and
 * the gesture comes back feeling dragged through mud.
 */
private data class ParsedMarkdown(val source: String, val root: ASTNode)

@Composable
fun MarkdownText(markdown: String, modifier: Modifier = Modifier) {
	val parsed by produceState<ParsedMarkdown?>(initialValue = null, markdown) {
		value = withContext(Dispatchers.Default) {
			val ast = MarkdownParser(GFMFlavourDescriptor()).buildMarkdownTreeFromString(markdown)
			ParsedMarkdown(source = markdown, root = ast)
		}
	}
	val current = parsed
	if (current == null || current.source != markdown) return

	val theme = MarkdownTheme(
		source = current.source,
		body = MaterialTheme.colorScheme.onSurface,
		link = MaterialTheme.colorScheme.primary,
		codeBackground = MaterialTheme.colorScheme.onSurface.copy(alpha = 0.08f),
	)
	Column(modifier = modifier) {
		Blocks(current.root, theme)
	}
}

/** Everything a render pass needs: the source to slice and the colours to draw with. */
private class MarkdownTheme(val source: String, val body: Color, val link: Color, val codeBackground: Color)

private fun ASTNode.textIn(theme: MarkdownTheme): String {
	val src = theme.source
	val start = startOffset.coerceIn(0, src.length)
	val end = endOffset.coerceIn(start, src.length)
	return src.substring(start, end)
}

// ------------------------------------------------------------------- blocks

@Composable
private fun Blocks(node: ASTNode, theme: MarkdownTheme) {
	when (node.type) {
		MarkdownElementTypes.PARAGRAPH ->
			Text(
				text = Inline(node, theme),
				color = theme.body,
				modifier = Modifier.padding(vertical = 4.dp),
			)

		MarkdownElementTypes.UNORDERED_LIST -> List(node, theme, ordered = false)

		MarkdownElementTypes.ORDERED_LIST -> List(node, theme, ordered = true)

		MarkdownElementTypes.ATX_1, MarkdownElementTypes.SETEXT_1 -> Heading(node, theme, 1)

		MarkdownElementTypes.ATX_2, MarkdownElementTypes.SETEXT_2 -> Heading(node, theme, 2)

		MarkdownElementTypes.ATX_3 -> Heading(node, theme, 3)

		MarkdownElementTypes.ATX_4, MarkdownElementTypes.ATX_5, MarkdownElementTypes.ATX_6 ->
			Heading(node, theme, 4)

		MarkdownElementTypes.CODE_FENCE, MarkdownElementTypes.CODE_BLOCK -> CodeBlock(node, theme)

		MarkdownElementTypes.BLOCK_QUOTE -> BlockQuote(node, theme)

		// Definitions and raw HTML blocks are not shown; the label of a
		// definition is already rendered at the point it is referenced.
		MarkdownElementTypes.LINK_DEFINITION, MarkdownElementTypes.HTML_BLOCK -> Unit

		else -> node.children.forEach { Blocks(it, theme) }
	}
}

@Composable
private fun List(node: ASTNode, theme: MarkdownTheme, ordered: Boolean) {
	var number = 0
	node.children
		.filter { it.type == MarkdownElementTypes.LIST_ITEM }
		.forEach { item ->
			val marker = if (ordered) "${++number}." else "\u2022"
			Row(modifier = Modifier.padding(vertical = 2.dp)) {
				Text(
					text = marker,
					color = theme.body,
					modifier = Modifier.padding(end = 8.dp, top = 2.dp),
				)
				Column(modifier = Modifier.weight(1f)) {
					item.children
						.filter {
							it.type != MarkdownTokenTypes.LIST_BULLET &&
								it.type != MarkdownTokenTypes.EOL
						}
						.forEach { Blocks(it, theme) }
				}
			}
		}
}

@Composable
private fun Heading(node: ASTNode, theme: MarkdownTheme, level: Int) {
	Text(
		text = buildAnnotatedString {
			node.children
				.filter { it.type != MarkdownTokenTypes.ATX_HEADER }
				.forEach { appendInline(it, theme) }
		},
		color = theme.body,
		fontSize = when (level) {
			1 -> 22.sp
			2 -> 20.sp
			3 -> 18.sp
			else -> 16.sp
		},
		fontWeight = FontWeight.Bold,
		modifier = Modifier.padding(top = 12.dp, bottom = 4.dp),
	)
}

@Composable
private fun CodeBlock(node: ASTNode, theme: MarkdownTheme) {
	Text(
		text = node.textIn(theme).trim('\n'),
		color = theme.body,
		fontFamily = FontFamily.Monospace,
		fontSize = 13.sp,
		modifier = Modifier
			.padding(vertical = 6.dp)
			.background(theme.codeBackground, RoundedCornerShape(8.dp))
			.padding(10.dp),
	)
}

@Composable
private fun BlockQuote(node: ASTNode, theme: MarkdownTheme) {
	Row(modifier = Modifier.padding(vertical = 4.dp)) {
		Box(
			modifier = Modifier
				.padding(end = 10.dp)
				.width(2.dp)
				.fillMaxHeight()
				.background(theme.link),
		)
		Text(
			text = Inline(node, theme),
			color = theme.body.copy(alpha = 0.85f),
			fontStyle = FontStyle.Italic,
		)
	}
}

// ------------------------------------------------------------------- inline

private fun Inline(node: ASTNode, theme: MarkdownTheme): AnnotatedString =
	buildAnnotatedString { appendInline(node, theme) }

private fun AnnotatedString.Builder.appendInline(node: ASTNode, theme: MarkdownTheme) {
	when (node.type) {
		MarkdownElementTypes.EMPH ->
			withStyle(SpanStyle(fontStyle = FontStyle.Italic)) {
				node.children.forEach { appendInline(it, theme) }
			}

		MarkdownElementTypes.STRONG ->
			withStyle(SpanStyle(fontWeight = FontWeight.Bold)) {
				node.children.forEach { appendInline(it, theme) }
			}

		MarkdownElementTypes.CODE_SPAN ->
			withStyle(
				SpanStyle(
					fontFamily = FontFamily.Monospace,
					background = theme.codeBackground,
				),
			) {
				append(node.textIn(theme).trim().trim('`'))
			}

		MarkdownElementTypes.INLINE_LINK,
		MarkdownElementTypes.FULL_REFERENCE_LINK,
		MarkdownElementTypes.SHORT_REFERENCE_LINK,
		-> {
			val label = node.children.firstOrNull { it.type == MarkdownElementTypes.LINK_TEXT }
			val destination =
				node.children.firstOrNull { it.type == MarkdownElementTypes.LINK_DESTINATION }
			if (label == null || destination == null) {
				node.children.forEach { appendInline(it, theme) }
				return
			}
			val url = destination.textIn(theme).trim()
			val start = length
			label.children.forEach { appendInline(it, theme) }
			val end = length
			if (url.isNotEmpty() && end > start) {
				addLink(
					LinkAnnotation.Url(
						url = url,
						styles = TextLinkStyles(
							style = SpanStyle(color = theme.link, textDecoration = TextDecoration.Underline),
						),
					),
					start = start,
					end = end,
				)
			}
		}

		MarkdownTokenTypes.EOL -> append(' ')

		MarkdownTokenTypes.HARD_LINE_BREAK -> append('\n')

		// Delimiters are syntax rather than content: the ** wrapped around a
		// strong span and the brackets around a link label belong to the
		// markdown, not to the text being read.
		MarkdownTokenTypes.EMPH,
		MarkdownTokenTypes.BACKTICK,
		MarkdownTokenTypes.LBRACKET,
		MarkdownTokenTypes.RBRACKET,
		MarkdownTokenTypes.EXCLAMATION_MARK,
		-> Unit

		else ->
			if (node.children.isEmpty()) {
				append(node.textIn(theme))
			} else {
				node.children.forEach { appendInline(it, theme) }
			}
	}
}

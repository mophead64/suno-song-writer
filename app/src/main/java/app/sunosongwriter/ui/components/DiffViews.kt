package app.sunosongwriter.ui.components

import androidx.compose.foundation.background
import androidx.compose.foundation.isSystemInDarkTheme
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.IntrinsicSize
import androidx.compose.foundation.layout.fillMaxHeight
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.width
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.Immutable
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.semantics.clearAndSetSemantics
import androidx.compose.ui.semantics.contentDescription
import androidx.compose.ui.text.AnnotatedString
import androidx.compose.ui.text.SpanStyle
import androidx.compose.ui.text.buildAnnotatedString
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextDecoration
import androidx.compose.ui.text.withStyle
import androidx.compose.ui.unit.dp
import app.sunosongwriter.domain.diff.ContentDiff
import app.sunosongwriter.domain.diff.DiffLine
import app.sunosongwriter.domain.diff.SideBySideContent
import app.sunosongwriter.domain.diff.SideRow
import app.sunosongwriter.domain.diff.Segment

@Immutable
data class DiffColors(val addedLine: Color, val removedLine: Color, val addedWord: Color, val removedWord: Color)

@Composable
fun diffColors(): DiffColors = if (isSystemInDarkTheme()) {
    DiffColors(Color(0xFF1C3520), Color(0xFF3D1E1E), Color(0xFF2E6B37), Color(0xFF7A2E2E))
} else {
    DiffColors(Color(0xFFE3F5E3), Color(0xFFFBE4E4), Color(0xFFB5E3B5), Color(0xFFF2B8B8))
}

private fun segmentsText(segments: List<Segment>, colors: DiffColors): AnnotatedString = buildAnnotatedString {
    segments.forEach { seg ->
        when (seg.kind) {
            Segment.Kind.SAME -> append(seg.text)
            Segment.Kind.ADDED -> withStyle(SpanStyle(background = colors.addedWord, fontWeight = FontWeight.SemiBold)) { append(seg.text) }
            Segment.Kind.REMOVED -> withStyle(SpanStyle(background = colors.removedWord, textDecoration = TextDecoration.LineThrough)) { append(seg.text) }
        }
    }
}

/** One diff row with a +/− gutter, so the meaning doesn't rely on colour alone. */
@Composable
private fun DiffRow(marker: String, text: AnnotatedString, background: Color?, description: String) {
    Row(
        Modifier
            .fillMaxWidth()
            .then(if (background != null) Modifier.background(background) else Modifier)
            .padding(horizontal = 8.dp, vertical = 2.dp)
            .semanticsDescription(description),
    ) {
        Text(marker, style = MaterialTheme.typography.bodyMedium, color = MaterialTheme.colorScheme.onSurfaceVariant, modifier = Modifier.width(18.dp))
        Text(text, style = MaterialTheme.typography.bodyMedium)
    }
}

@Composable
fun LyricsDiff(lines: List<DiffLine>, modifier: Modifier = Modifier) {
    val colors = diffColors()
    Column(modifier) {
        lines.forEach { line ->
            when (line) {
                is DiffLine.Same -> DiffRow(" ", AnnotatedString(line.text), null, line.text)
                is DiffLine.Added -> DiffRow("+", AnnotatedString(line.text), colors.addedLine, "Added: ${line.text}")
                is DiffLine.Removed -> DiffRow("−", AnnotatedString(line.text, SpanStyle(textDecoration = TextDecoration.LineThrough)), colors.removedLine, "Removed: ${line.text}")
                is DiffLine.Changed -> {
                    DiffRow("−", segmentsText(line.oldSegments, colors), colors.removedLine, "Changed from: ${line.old}")
                    DiffRow("+", segmentsText(line.newSegments, colors), colors.addedLine, "Changed to: ${line.new}")
                }
            }
        }
    }
}

@Composable
fun ContentDiffView(diff: ContentDiff, modifier: Modifier = Modifier) {
    val colors = diffColors()
    Column(modifier, verticalArrangement = Arrangement.spacedBy(16.dp)) {
        if (diff.isEmpty) {
            Text("No differences.", style = MaterialTheme.typography.bodyMedium)
            return@Column
        }
        if (diff.settings.isNotEmpty()) {
            DiffHeading("Settings")
            diff.settings.forEach { s ->
                Text("${s.label}: ${s.old ?: "not set"} → ${s.new ?: "not set"}", style = MaterialTheme.typography.bodyMedium)
            }
        }
        if (diff.style.isNotEmpty()) {
            DiffHeading("Style")
            diff.style.forEach { s ->
                Column(verticalArrangement = Arrangement.spacedBy(2.dp)) {
                    Text(s.section.label, style = MaterialTheme.typography.labelLarge, color = MaterialTheme.colorScheme.primary)
                    when {
                        s.old.isEmpty() -> DiffRow("+", AnnotatedString(s.new), colors.addedLine, "${s.section.label} added: ${s.new}")
                        s.new.isEmpty() -> DiffRow("−", AnnotatedString(s.old, SpanStyle(textDecoration = TextDecoration.LineThrough)), colors.removedLine, "${s.section.label} removed: ${s.old}")
                        else -> DiffRow("~", segmentsText(s.segments, colors), null, "${s.section.label} changed from ${s.old} to ${s.new}")
                    }
                }
            }
        }
        if (diff.lyricsChanged) {
            DiffHeading("Lyrics")
            LyricsDiff(diff.lyrics)
        }
    }
}

@Composable
private fun DiffHeading(text: String) {
    Text(text, style = MaterialTheme.typography.titleMedium)
}

/** Screen readers hear "Added: …" / "Removed: …" instead of the marker and highlighted fragments. */
private fun Modifier.semanticsDescription(description: String): Modifier =
    clearAndSetSemantics { contentDescription = description }

/** Two variants in columns (spec 5.15). Changed cells are tinted, with word-level highlights. */
@Composable
fun SideBySideView(content: SideBySideContent, leftLabel: String, rightLabel: String, modifier: Modifier = Modifier) {
    val colors = diffColors()
    Column(modifier, verticalArrangement = Arrangement.spacedBy(16.dp)) {
        SideRowLayout(
            left = { Text(leftLabel, style = MaterialTheme.typography.titleSmall) },
            right = { Text(rightLabel, style = MaterialTheme.typography.titleSmall) },
        )
        if (content.settings.isNotEmpty()) {
            DiffHeading("Settings")
            Column {
                content.settings.forEach { s ->
                    val changed = s.old != s.new
                    SideRowLayout(
                        left = { SideCell("${s.label}: ${s.old ?: "not set"}", if (changed) colors.removedLine else null, "$leftLabel ${s.label}: ${s.old ?: "not set"}") },
                        right = { SideCell("${s.label}: ${s.new ?: "not set"}", if (changed) colors.addedLine else null, "$rightLabel ${s.label}: ${s.new ?: "not set"}") },
                    )
                }
            }
        }
        if (content.style.isNotEmpty()) {
            DiffHeading("Style")
            content.style.forEach { s ->
                Column(verticalArrangement = Arrangement.spacedBy(2.dp)) {
                    Text(s.section.label, style = MaterialTheme.typography.labelLarge, color = MaterialTheme.colorScheme.primary)
                    SideCells(s.row, colors, leftLabel, rightLabel)
                }
            }
        }
        DiffHeading("Lyrics")
        if (content.lyrics.isEmpty()) Text("No lyrics on either side.", style = MaterialTheme.typography.bodyMedium)
        Column { content.lyrics.forEach { SideCells(it, colors, leftLabel, rightLabel) } }
    }
}

@Composable
private fun SideCells(row: SideRow, colors: DiffColors, leftLabel: String, rightLabel: String) {
    val changed = row.changed
    SideRowLayout(
        left = {
            row.left?.let { segs ->
                SideCell(segmentsText(segs, colors), if (changed) colors.removedLine else null, "$leftLabel: ${segs.joinToString("") { it.text }}")
            }
        },
        right = {
            row.right?.let { segs ->
                SideCell(segmentsText(segs, colors), if (changed) colors.addedLine else null, "$rightLabel: ${segs.joinToString("") { it.text }}")
            }
        },
    )
}

@Composable
private fun SideRowLayout(left: @Composable () -> Unit, right: @Composable () -> Unit) {
    Row(Modifier.fillMaxWidth().height(IntrinsicSize.Min), horizontalArrangement = Arrangement.spacedBy(8.dp)) {
        Box(Modifier.weight(1f).fillMaxHeight()) { left() }
        Box(Modifier.weight(1f).fillMaxHeight()) { right() }
    }
}

@Composable
private fun SideCell(text: String, background: Color?, description: String) = SideCell(AnnotatedString(text), background, description)

@Composable
private fun SideCell(text: AnnotatedString, background: Color?, description: String) {
    Text(
        text,
        style = MaterialTheme.typography.bodySmall,
        modifier = Modifier
            .fillMaxSize()
            .then(if (background != null) Modifier.background(background) else Modifier)
            .padding(horizontal = 4.dp, vertical = 2.dp)
            .semanticsDescription(description),
    )
}

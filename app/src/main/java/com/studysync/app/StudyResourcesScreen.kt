package com.studysync.app

import android.content.ActivityNotFoundException
import android.content.Context
import android.content.Intent
import android.net.Uri
import android.util.Log
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.material3.*
import androidx.compose.runtime.*
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.semantics.heading
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.unit.dp

internal data class StudyGuide(
    val id: String,
    val title: Int,
    val summary: Int,
    val steps: Int,
    val citation: String,
    val url: String
)

// Original study prompts summarise the research linked below; they are not quotations.
internal val studyGuides = listOf(
    StudyGuide("recall", R.string.sr_recall_title, R.string.sr_recall_summary, R.string.sr_recall_steps,
        "Roediger, H.L. and Karpicke, J.D. (2006). Test-enhanced learning: Taking memory tests improves long-term retention. Psychological Science, 17(3), pp. 249-255.",
        "https://doi.org/10.1111/j.1467-9280.2006.01693.x"),
    StudyGuide("spacing", R.string.sr_spacing_title, R.string.sr_spacing_summary, R.string.sr_spacing_steps,
        "Cepeda, N.J., Pashler, H., Vul, E., Wixted, J.T. and Rohrer, D. (2006). Distributed practice in verbal recall tasks: A review and quantitative synthesis. Psychological Bulletin, 132(3), pp. 354-380.",
        "https://doi.org/10.1037/0033-2909.132.3.354"),
    StudyGuide("interleaving", R.string.sr_mix_title, R.string.sr_mix_summary, R.string.sr_mix_steps,
        "Rohrer, D. and Taylor, K. (2007). The shuffling of mathematics problems improves learning. Instructional Science, 35, pp. 481-498.",
        "https://doi.org/10.1007/s11251-007-9015-8"),
    StudyGuide("explanation", R.string.sr_explain_title, R.string.sr_explain_summary, R.string.sr_explain_steps,
        "Chi, M.T.H., Bassok, M., Lewis, M.W., Reimann, P. and Glaser, R. (1989). Self-explanations: How students study and use examples in learning to solve problems. Cognitive Science, 13(2), pp. 145-182.",
        "https://doi.org/10.1207/s15516709cog1302_1")
)

// This video is linked by its creator at https://www.learningscientists.org/videos.
internal const val STUDY_VIDEO_URL = "https://www.youtube.com/watch?v=jfREWvtd_DM"

internal fun openStudyLink(context: Context, url: String): Boolean {
    val uri = Uri.parse(url)
    // Only the curated HTTPS destinations are handed to another app.
    if (uri.scheme != "https" || uri.userInfo != null ||
        uri.host !in setOf("doi.org", "www.youtube.com")
    ) return false
    return try {
        context.startActivity(Intent(Intent.ACTION_VIEW, uri)
            .addCategory(Intent.CATEGORY_BROWSABLE)
            .addFlags(Intent.FLAG_ACTIVITY_NEW_TASK))
        true
    } catch (exception: ActivityNotFoundException) {
        Log.w("StudySync", "No app can open the study resource", exception)
        false
    } catch (exception: SecurityException) {
        Log.w("StudySync", "Study resource launch was blocked", exception)
        false
    }
}

@Composable
fun StudyResourcesScreen(onOpenFocus: () -> Unit) {
    val context = LocalContext.current
    var expanded by rememberSaveable { mutableStateOf<String?>(null) }
    var failedUrl by rememberSaveable { mutableStateOf<String?>(null) }
    val open: (String) -> Unit = { url ->
        failedUrl = if (openStudyLink(context, url)) null else url
    }

    LaunchedEffect(Unit) { Log.d("StudySync", "Study resources opened") }

    LazyColumn(
        modifier = Modifier.fillMaxSize(),
        contentPadding = PaddingValues(16.dp),
        verticalArrangement = Arrangement.spacedBy(16.dp)
    ) {
        item {
            Column(verticalArrangement = Arrangement.spacedBy(8.dp)) {
                Text(context.getString(R.string.sr_title), style = MaterialTheme.typography.headlineSmall,
                    modifier = Modifier.semantics { heading() })
                Text(context.getString(R.string.sr_intro))
            }
        }
        items(studyGuides, key = { it.id }) { guide ->
            Card(Modifier.fillMaxWidth()) {
                Column(Modifier.padding(16.dp), verticalArrangement = Arrangement.spacedBy(10.dp)) {
                    Text(context.getString(guide.title), style = MaterialTheme.typography.titleMedium,
                        modifier = Modifier.semantics { heading() })
                    Text(context.getString(guide.summary))
                    OutlinedButton(onClick = {
                        expanded = if (expanded == guide.id) null else guide.id
                    }, modifier = Modifier.fillMaxWidth()) {
                        Text(context.getString(if (expanded == guide.id) R.string.sr_hide else R.string.sr_read))
                    }
                    if (expanded == guide.id) {
                        Text(context.getString(R.string.sr_steps), style = MaterialTheme.typography.labelLarge)
                        Text(context.getString(guide.steps))
                        HorizontalDivider()
                        Text(context.getString(R.string.sr_reference), style = MaterialTheme.typography.labelLarge)
                        Text(guide.citation, style = MaterialTheme.typography.bodySmall)
                        TextButton(onClick = { open(guide.url) }) {
                            Text(context.getString(R.string.sr_reference_button))
                        }
                        if (failedUrl == guide.url) {
                            Text(context.getString(R.string.sr_link_error), color = MaterialTheme.colorScheme.error)
                        }
                    }
                }
            }
        }
        item {
            Card(Modifier.fillMaxWidth()) {
                Column(Modifier.padding(16.dp), verticalArrangement = Arrangement.spacedBy(10.dp)) {
                    Text(context.getString(R.string.sr_video_title), style = MaterialTheme.typography.titleMedium,
                        modifier = Modifier.semantics { heading() })
                    Text(context.getString(R.string.sr_video_info))
                    Text(context.getString(R.string.sr_video_credit), style = MaterialTheme.typography.bodySmall)
                    Button(onClick = { open(STUDY_VIDEO_URL) }, modifier = Modifier.fillMaxWidth()) {
                        Text(context.getString(R.string.sr_video_button))
                    }
                    if (failedUrl == STUDY_VIDEO_URL) {
                        Text(context.getString(R.string.sr_link_error), color = MaterialTheme.colorScheme.error)
                    }
                }
            }
        }
        item {
            Card(Modifier.fillMaxWidth()) {
                Column(Modifier.padding(16.dp), verticalArrangement = Arrangement.spacedBy(10.dp)) {
                    Text(context.getString(R.string.sr_focus_title), style = MaterialTheme.typography.titleMedium,
                        modifier = Modifier.semantics { heading() })
                    Text(context.getString(R.string.sr_focus_info))
                    OutlinedButton(onClick = onOpenFocus, modifier = Modifier.fillMaxWidth()) {
                        Text(context.getString(R.string.sr_focus_button))
                    }
                }
            }
        }
    }
}
